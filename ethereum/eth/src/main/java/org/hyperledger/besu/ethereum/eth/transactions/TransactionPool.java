/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.eth.transactions;

import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static org.apache.logging.log4j.LogManager.getLogger;
import static org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason.CHAIN_HEAD_WORLD_STATE_NOT_AVAILABLE;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.BlockAddedEvent;
import org.hyperledger.besu.ethereum.chain.BlockAddedObserver;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.core.fees.BaseFee;
import org.hyperledger.besu.ethereum.core.fees.EIP1559;
import org.hyperledger.besu.ethereum.core.fees.TransactionPriceCalculator;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactions.TransactionAddedStatus;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionValidator;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.data.TransactionType;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.Logger;

/**
 * Maintains the set of pending transactions received from JSON-RPC or other nodes. Transactions are
 * removed automatically when they are included in a block on the canonical chain and re-added if a
 * re-org removes them from the canonical chain again.
 *
 * <p>This class is safe for use across multiple threads.
 */
public class TransactionPool implements BlockAddedObserver {

  private static final Logger LOG = getLogger();

  private static final long SYNC_TOLERANCE = 100L;
  private static final String REMOTE = "remote";
  private static final String LOCAL = "local";
  private final PendingTransactions pendingTransactions;
  private final ProtocolSchedule protocolSchedule;
  private final ProtocolContext protocolContext;
  private final TransactionBatchAddedListener transactionBatchAddedListener;
  private final TransactionBatchAddedListener pendingTransactionBatchAddedListener;
  private final SyncState syncState;
  private final Wei minTransactionGasPrice;
  private final LabelledMetric<Counter> duplicateTransactionCounter;
  private final PeerTransactionTracker peerTransactionTracker;
  private final PeerPendingTransactionTracker peerPendingTransactionTracker;
  private final TransactionPriceCalculator frontierPriceCalculator =
      TransactionPriceCalculator.frontier();
  private final TransactionPriceCalculator eip1559PriceCalculator =
      TransactionPriceCalculator.eip1559();
  private final TransactionPoolConfiguration configuration;

  public TransactionPool(
      final PendingTransactions pendingTransactions,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final TransactionBatchAddedListener transactionBatchAddedListener,
      final TransactionBatchAddedListener pendingTransactionBatchAddedListener,
      final SyncState syncState,
      final EthContext ethContext,
      final PeerTransactionTracker peerTransactionTracker,
      final PeerPendingTransactionTracker peerPendingTransactionTracker,
      final Wei minTransactionGasPrice,
      final MetricsSystem metricsSystem,
      final TransactionPoolConfiguration configuration) {
    this.pendingTransactions = pendingTransactions;
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
    this.transactionBatchAddedListener = transactionBatchAddedListener;
    this.pendingTransactionBatchAddedListener = pendingTransactionBatchAddedListener;
    this.syncState = syncState;
    this.peerTransactionTracker = peerTransactionTracker;
    this.peerPendingTransactionTracker = peerPendingTransactionTracker;
    this.minTransactionGasPrice = minTransactionGasPrice;
    this.configuration = configuration;

    duplicateTransactionCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "transactions_duplicates_total",
            "Total number of duplicate transactions received",
            "source");

    ethContext.getEthPeers().subscribeConnect(this::handleConnect);
  }

  void handleConnect(final EthPeer peer) {
    pendingTransactions
        .getLocalTransactions()
        .forEach(transaction -> peerTransactionTracker.addToPeerSendQueue(peer, transaction));

    if (peerPendingTransactionTracker.isPeerSupported(peer, EthProtocol.ETH65)) {
      pendingTransactions
          .getNewPooledHashes()
          .forEach(hash -> peerPendingTransactionTracker.addToPeerSendQueue(peer, hash));
    }
  }

  public boolean addTransactionHash(final Hash transactionHash) {
    return pendingTransactions.addTransactionHash(transactionHash);
  }

  public ValidationResult<TransactionInvalidReason> addLocalTransaction(
      final Transaction transaction) {
    final ValidationResult<TransactionInvalidReason> validationResult =
        validateTransaction(transaction);
    if (validationResult.isValid()) {
      if (!configuration.getTxFeeCap().isZero()
          && minTransactionGasPrice(transaction).compareTo(configuration.getTxFeeCap()) > 0) {
        return ValidationResult.invalid(TransactionInvalidReason.TX_FEECAP_EXCEEDED);
      }
      final TransactionAddedStatus transactionAddedStatus =
          pendingTransactions.addLocalTransaction(transaction);
      if (!transactionAddedStatus.equals(TransactionAddedStatus.ADDED)) {
        duplicateTransactionCounter.labels(LOCAL).inc();
        return ValidationResult.invalid(transactionAddedStatus.getInvalidReason().orElseThrow());
      }
      final Collection<Transaction> txs = singletonList(transaction);
      transactionBatchAddedListener.onTransactionsAdded(txs);
      pendingTransactionBatchAddedListener.onTransactionsAdded(txs);
    }

    return validationResult;
  }

  public void addRemoteTransactions(final Collection<Transaction> transactions) {
    if (!syncState.isInSync(SYNC_TOLERANCE)) {
      return;
    }
    final Set<Transaction> addedTransactions = new HashSet<>();
    for (final Transaction transaction : transactions) {
      pendingTransactions.tryEvictTransactionHash(transaction.getHash());
      if (pendingTransactions.containsTransaction(transaction.getHash())) {
        // We already have this transaction, don't even validate it.
        duplicateTransactionCounter.labels(REMOTE).inc();
        continue;
      }
      final Wei transactionGasPrice = minTransactionGasPrice(transaction);
      if (transactionGasPrice.compareTo(minTransactionGasPrice) < 0) {
        continue;
      }
      final ValidationResult<TransactionInvalidReason> validationResult =
          validateTransaction(transaction);
      if (validationResult.isValid()) {
        final boolean added = pendingTransactions.addRemoteTransaction(transaction);
        if (added) {
          addedTransactions.add(transaction);
        } else {
          duplicateTransactionCounter.labels(REMOTE).inc();
        }
      } else {
        LOG.trace(
            "Validation failed ({}) for transaction {}. Discarding.",
            validationResult.getInvalidReason(),
            transaction);
      }
    }
    if (!addedTransactions.isEmpty()) {
      transactionBatchAddedListener.onTransactionsAdded(addedTransactions);
    }
  }

  public long subscribePendingTransactions(final PendingTransactionListener listener) {
    return pendingTransactions.subscribePendingTransactions(listener);
  }

  public void unsubscribePendingTransactions(final long id) {
    pendingTransactions.unsubscribePendingTransactions(id);
  }

  public long subscribeDroppedTransactions(final PendingTransactionDroppedListener listener) {
    return pendingTransactions.subscribeDroppedTransactions(listener);
  }

  public void unsubscribeDroppedTransactions(final long id) {
    pendingTransactions.unsubscribeDroppedTransactions(id);
  }

  @Override
  public void onBlockAdded(final BlockAddedEvent event) {
    event.getAddedTransactions().forEach(pendingTransactions::transactionAddedToBlock);
    event.getBlock().getHeader().getBaseFee().ifPresent(pendingTransactions::updateBaseFee);
    addRemoteTransactions(event.getRemovedTransactions());
  }

  private MainnetTransactionValidator getTransactionValidator() {
    return protocolSchedule
        .getByBlockNumber(protocolContext.getBlockchain().getChainHeadBlockNumber())
        .getTransactionValidator();
  }

  public PendingTransactions getPendingTransactions() {
    return pendingTransactions;
  }

  private ValidationResult<TransactionInvalidReason> validateTransaction(
      final Transaction transaction) {
    final BlockHeader chainHeadBlockHeader = getChainHeadBlockHeader();

    // Check whether it's a GoQuorum transaction
    if (transaction.isGoQuorumPrivateTransaction()) {
      final Optional<Wei> weiValue = ofNullable(transaction.getValue());
      if (weiValue.isPresent() && !weiValue.get().isZero()) {
        return ValidationResult.invalid(TransactionInvalidReason.ETHER_VALUE_NOT_SUPPORTED);
      }
    }

    final ValidationResult<TransactionInvalidReason> basicValidationResult =
        getTransactionValidator()
            .validate(
                transaction,
                chainHeadBlockHeader.getBaseFee(),
                TransactionValidationParams.transactionPool());
    if (!basicValidationResult.isValid()) {
      return basicValidationResult;
    }

    if (transaction.getGasLimit() > chainHeadBlockHeader.getGasLimit()) {
      return ValidationResult.invalid(
          TransactionInvalidReason.EXCEEDS_BLOCK_GAS_LIMIT,
          String.format(
              "Transaction gas limit of %s exceeds block gas limit of %s",
              transaction.getGasLimit(), chainHeadBlockHeader.getGasLimit()));
    }
    if (transaction.getType().equals(TransactionType.EIP1559)) {
      final long blknum = chainHeadBlockHeader.getNumber();
      ProtocolSpec spec = protocolSchedule.getByBlockNumber(blknum);
      if (!spec.getEip1559().map(eip1559 -> eip1559.isEIP1559(blknum)).orElse(false)) {
        return ValidationResult.invalid(
            TransactionInvalidReason.INVALID_TRANSACTION_FORMAT,
            "EIP-1559 transaction are not allowed yet");
      }
    }

    return protocolContext
        .getWorldStateArchive()
        .getMutable(chainHeadBlockHeader.getStateRoot(), chainHeadBlockHeader.getHash(), false)
        .map(
            worldState -> {
              final Account senderAccount = worldState.get(transaction.getSender());
              return getTransactionValidator()
                  .validateForSender(
                      transaction, senderAccount, TransactionValidationParams.transactionPool());
            })
        .orElseGet(() -> ValidationResult.invalid(CHAIN_HEAD_WORLD_STATE_NOT_AVAILABLE));
  }

  public Optional<Transaction> getTransactionByHash(final Hash hash) {
    return pendingTransactions.getTransactionByHash(hash);
  }

  private BlockHeader getChainHeadBlockHeader() {
    final MutableBlockchain blockchain = protocolContext.getBlockchain();
    return blockchain.getBlockHeader(blockchain.getChainHeadHash()).get();
  }

  public interface TransactionBatchAddedListener {

    void onTransactionsAdded(Iterable<Transaction> transactions);
  }

  private Wei minTransactionGasPrice(final Transaction transaction) {
    final BlockHeader chainHeadBlockHeader = getChainHeadBlockHeader();
    // Compute transaction price using EIP-1559 rules if chain head is after fork
    ProtocolSpec spec = protocolSchedule.getByBlockNumber(chainHeadBlockHeader.getNumber());
    Optional<FeeMarket> feeMarket = spec.getFeeMarket();
    // TODO: remove once eip1559 is part of FeeMarket
    Optional<EIP1559> eip1559 = spec.getEip1559();

    if (feeMarket
        .flatMap(fm -> eip1559.map(e -> e.isEIP1559(chainHeadBlockHeader.getNumber())))
        .orElse(Boolean.FALSE)) {
      // TODO: remove eip1559PriceCalculator once it is rolled into fee market
      return BaseFee.minTransactionPriceInNextBlock(
          transaction, feeMarket.get(), eip1559PriceCalculator, chainHeadBlockHeader::getBaseFee);
    } else { // Use frontier rules otherwise
      return frontierPriceCalculator.price(transaction, Optional.empty());
    }
  }
}
