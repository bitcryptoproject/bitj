/*
 * Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.core;

import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.utils.ListenerRegistration;
import com.google.bitcoin.utils.Threading;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.*;

/**
 * <p>An AbstractBlockChain holds a series of {@link Block} objects, links them together, and knows how to verify that
 * the chain follows the rules of the {@link NetworkParameters} for this chain.</p>
 *
 * <p>It can be connected to a {@link Wallet}, and also {@link BlockChainListener}s that can receive transactions and
 * notifications of re-organizations.</p>
 *
 * <p>An AbstractBlockChain implementation must be connected to a {@link BlockStore} implementation. The chain object
 * by itself doesn't store any data, that's delegated to the store. Which store you use is a decision best made by
 * reading the getting started guide, but briefly, fully validating block chains need fully validating stores. In
 * the lightweight SPV mode, a {@link com.google.bitcoin.store.SPVBlockStore} is the right choice.</p>
 *
 * <p>This class implements an abstract class which makes it simple to create a BlockChain that does/doesn't do full
 * verification.  It verifies headers and is implements most of what is required to implement SPV mode, but
 * also provides callback hooks which can be used to do full verification.</p>
 *
 * <p>There are two subclasses of AbstractBlockChain that are useful: {@link BlockChain}, which is the simplest
 * class and implements <i>simplified payment verification</i>. This is a lightweight and efficient mode that does
 * not verify the contents of blocks, just their headers. A {@link FullPrunedBlockChain} paired with a
 * {@link com.google.bitcoin.store.H2FullPrunedBlockStore} implements full verification, which is equivalent to the
 * original Satoshi client. To learn more about the alternative security models, please consult the articles on the
 * website.</p>
 *
 * <b>Theory</b>
 *
 * <p>The 'chain' is actually a tree although in normal operation it operates mostly as a list of {@link Block}s.
 * When multiple new head blocks are found simultaneously, there are multiple stories of the economy competing to become
 * the one true consensus. This can happen naturally when two miners solve a block within a few seconds of each other,
 * or it can happen when the chain is under attack.</p>
 *
 * <p>A reference to the head block of the best known chain is stored. If you can reach the genesis block by repeatedly
 * walking through the prevBlock pointers, then we say this is a full chain. If you cannot reach the genesis block
 * we say it is an orphan chain. Orphan chains can occur when blocks are solved and received during the initial block
 * chain download, or if we connect to a peer that doesn't send us blocks in order.</p>
 *
 * <p>A reorganize occurs when the blocks that make up the best known chain changes. Note that simply adding a
 * new block to the top of the best chain isn't as reorganize, but that a reorganize is always triggered by adding
 * a new block that connects to some other (non best head) block. By "best" we mean the chain representing the largest
 * amount of work done.</p>
 *
 * <p>Every so often the block chain passes a difficulty transition point. At that time, all the blocks in the last
 * 2016 blocks are examined and a new difficulty target is calculated from them.</p>
 */
public abstract class AbstractBlockChain {
    private static final Logger log = LoggerFactory.getLogger(AbstractBlockChain.class);
    protected final ReentrantLock lock = Threading.lock("blockchain");

    /** Keeps a map of block hashes to StoredBlocks. */
    private final BlockStore blockStore;

    /**
     * Tracks the top of the best known chain.<p>
     *
     * Following this one down to the genesis block produces the story of the economy from the creation of Bitcoin
     * until the present day. The chain head can change if a new set of blocks is received that results in a chain of
     * greater work than the one obtained by following this one down. In that case a reorganize is triggered,
     * potentially invalidating transactions in our wallet.
     */
    protected StoredBlock chainHead;

    // TODO: Scrap this and use a proper read/write for all of the block chain objects.
    // The chainHead field is read/written synchronized with this object rather than BlockChain. However writing is
    // also guaranteed to happen whilst BlockChain is synchronized (see setChainHead). The goal of this is to let
    // clients quickly access the chain head even whilst the block chain is downloading and thus the BlockChain is
    // locked most of the time.
    private final Object chainHeadLock = new Object();

    protected final NetworkParameters params;
    private final CopyOnWriteArrayList<ListenerRegistration<BlockChainListener>> listeners;

    // Holds a block header and, optionally, a list of tx hashes or block's transactions
    class OrphanBlock {
        final Block block;
        final List<Sha256Hash> filteredTxHashes;
        final Map<Sha256Hash, Transaction> filteredTxn;
        OrphanBlock(Block block, @Nullable List<Sha256Hash> filteredTxHashes, @Nullable Map<Sha256Hash, Transaction> filteredTxn) {
            final boolean filtered = filteredTxHashes != null && filteredTxn != null;
            Preconditions.checkArgument((block.transactions == null && filtered)
                                        || (block.transactions != null && !filtered));
            if (!shouldVerifyTransactions())
                this.block = block.cloneAsHeader();
            else
                this.block = block;
            this.filteredTxHashes = filteredTxHashes;
            this.filteredTxn = filteredTxn;
        }
    }
    // Holds blocks that we have received but can't plug into the chain yet, eg because they were created whilst we
    // were downloading the block chain.
    private final LinkedHashMap<Sha256Hash, OrphanBlock> orphanBlocks = new LinkedHashMap<Sha256Hash, OrphanBlock>();

    /** False positive estimation uses a double exponential moving average. */
    public static final double FP_ESTIMATOR_ALPHA = 0.0001;
    /** False positive estimation uses a double exponential moving average. */
    public static final double FP_ESTIMATOR_BETA = 0.01;

    private double falsePositiveRate;
    private double falsePositiveTrend;
    private double previousFalsePositiveRate;


    /**
     * Constructs a BlockChain connected to the given list of listeners (eg, wallets) and a store.
     */
    public AbstractBlockChain(NetworkParameters params, List<BlockChainListener> listeners,
                              BlockStore blockStore) throws BlockStoreException {
        this.blockStore = blockStore;
        chainHead = blockStore.getChainHead();
        log.info("chain head is at height {}:\n{}", chainHead.getHeight(), chainHead.getHeader());
        this.params = params;
        this.listeners = new CopyOnWriteArrayList<ListenerRegistration<BlockChainListener>>();
        for (BlockChainListener l : listeners) addListener(l, Threading.SAME_THREAD);
    }

    /**
     * Add a wallet to the BlockChain. Note that the wallet will be unaffected by any blocks received while it
     * was not part of this BlockChain. This method is useful if the wallet has just been created, and its keys
     * have never been in use, or if the wallet has been loaded along with the BlockChain. Note that adding multiple
     * wallets is not well tested!
     */
    public void addWallet(Wallet wallet) {
        addListener(wallet, Threading.SAME_THREAD);
        if (wallet.getLastBlockSeenHeight() != getBestChainHeight()) {
            log.warn("Wallet/chain height mismatch: {} vs {}", wallet.getLastBlockSeenHeight(), getBestChainHeight());
            log.warn("Hashes: {} vs {}", wallet.getLastBlockSeenHash(), getChainHead().getHeader().getHash());
        }
    }

    /** Removes a wallet from the chain. */
    public void removeWallet(Wallet wallet) {
        removeListener(wallet);
    }

    /**
     * Adds a generic {@link BlockChainListener} listener to the chain.
     */
    public void addListener(BlockChainListener listener) {
        addListener(listener, Threading.SAME_THREAD);
    }

    /**
     * Adds a generic {@link BlockChainListener} listener to the chain.
     */
    public void addListener(BlockChainListener listener, Executor executor) {
        listeners.add(new ListenerRegistration<BlockChainListener>(listener, executor));
    }

    /**
     * Removes the given {@link BlockChainListener} from the chain.
     */
    public void removeListener(BlockChainListener listener) {
        ListenerRegistration.removeFromList(listener, listeners);
    }
    
    /**
     * Returns the {@link BlockStore} the chain was constructed with. You can use this to iterate over the chain.
     */
    public BlockStore getBlockStore() {
        return blockStore;
    }
    
    /**
     * Adds/updates the given {@link Block} with the block store.
     * This version is used when the transactions have not been verified.
     * @param storedPrev The {@link StoredBlock} which immediately precedes block.
     * @param block The {@link Block} to add/update.
     * @return the newly created {@link StoredBlock}
     */
    protected abstract StoredBlock addToBlockStore(StoredBlock storedPrev, Block block)
            throws BlockStoreException, VerificationException;
    
    /**
     * Adds/updates the given {@link StoredBlock} with the block store.
     * This version is used when the transactions have already been verified to properly spend txOutputChanges.
     * @param storedPrev The {@link StoredBlock} which immediately precedes block.
     * @param header The {@link StoredBlock} to add/update.
     * @param txOutputChanges The total sum of all changes made by this block to the set of open transaction outputs
     *                        (from a call to connectTransactions), if in fully verifying mode (null otherwise).
     * @return the newly created {@link StoredBlock}
     */
    protected abstract StoredBlock addToBlockStore(StoredBlock storedPrev, Block header,
                                                   @Nullable TransactionOutputChanges txOutputChanges)
            throws BlockStoreException, VerificationException;
    
    /**
     * Called before setting chain head in memory.
     * Should write the new head to block store and then commit any database transactions
     * that were started by disconnectTransactions/connectTransactions.
     */
    protected abstract void doSetChainHead(StoredBlock chainHead) throws BlockStoreException;
    
    /**
     * Called if we (possibly) previously called disconnectTransaction/connectTransactions,
     * but will not be calling preSetChainHead as a block failed verification.
     * Can be used to abort database transactions that were started by
     * disconnectTransactions/connectTransactions.
     */
    protected abstract void notSettingChainHead() throws BlockStoreException;
    
    /**
     * For a standard BlockChain, this should return blockStore.get(hash),
     * for a FullPrunedBlockChain blockStore.getOnceUndoableStoredBlock(hash)
     */
    protected abstract StoredBlock getStoredBlockInCurrentScope(Sha256Hash hash) throws BlockStoreException;

    /**
     * Processes a received block and tries to add it to the chain. If there's something wrong with the block an
     * exception is thrown. If the block is OK but cannot be connected to the chain at this time, returns false.
     * If the block can be connected to the chain, returns true.
     * Accessing block's transactions in another thread while this method runs may result in undefined behavior.
     */
    public boolean add(Block block) throws VerificationException, PrunedException {
        try {
            return add(block, true, null, null);
        } catch (BlockStoreException e) {
            // TODO: Figure out a better way to propagate this exception to the user.
            throw new RuntimeException(e);
        } catch (VerificationException e) {
            try {
                notSettingChainHead();
            } catch (BlockStoreException e1) {
                throw new RuntimeException(e1);
            }
            throw new VerificationException("Could not verify block " + block.getHashAsString() + "\n" +
                    block.toString(), e);
        }
    }
    
    /**
     * Processes a received block and tries to add it to the chain. If there's something wrong with the block an
     * exception is thrown. If the block is OK but cannot be connected to the chain at this time, returns false.
     * If the block can be connected to the chain, returns true.
     */
    public boolean add(FilteredBlock block) throws VerificationException, PrunedException {
        try {
            // The block has a list of hashes of transactions that matched the Bloom filter, and a list of associated
            // Transaction objects. There may be fewer Transaction objects than hashes, this is expected. It can happen
            // in the case where we were already around to witness the initial broadcast, so we downloaded the
            // transaction and sent it to the wallet before this point (the wallet may have thrown it away if it was
            // a false positive, as expected in any Bloom filtering scheme). The filteredTxn list here will usually
            // only be full of data when we are catching up to the head of the chain and thus haven't witnessed any
            // of the transactions.
            return add(block.getBlockHeader(), true, block.getTransactionHashes(), block.getAssociatedTransactions());
        } catch (BlockStoreException e) {
            // TODO: Figure out a better way to propagate this exception to the user.
            throw new RuntimeException(e);
        } catch (VerificationException e) {
            try {
                notSettingChainHead();
            } catch (BlockStoreException e1) {
                throw new RuntimeException(e1);
            }
            throw new VerificationException("Could not verify block " + block.getHash().toString() + "\n" +
                    block.toString(), e);
        }
    }
    
    /**
     * Whether or not we are maintaining a set of unspent outputs and are verifying all transactions.
     * Also indicates that all calls to add() should provide a block containing transactions
     */
    protected abstract boolean shouldVerifyTransactions();
    
    /**
     * Connect each transaction in block.transactions, verifying them as we go and removing spent outputs
     * If an error is encountered in a transaction, no changes should be made to the underlying BlockStore.
     * and a VerificationException should be thrown.
     * Only called if(shouldVerifyTransactions())
     * @throws VerificationException if an attempt was made to spend an already-spent output, or if a transaction incorrectly solved an output script.
     * @throws BlockStoreException if the block store had an underlying error.
     * @return The full set of all changes made to the set of open transaction outputs.
     */
    protected abstract TransactionOutputChanges connectTransactions(int height, Block block) throws VerificationException, BlockStoreException;

    /**
     * Load newBlock from BlockStore and connect its transactions, returning changes to the set of unspent transactions.
     * If an error is encountered in a transaction, no changes should be made to the underlying BlockStore.
     * Only called if(shouldVerifyTransactions())
     * @throws PrunedException if newBlock does not exist as a {@link StoredUndoableBlock} in the block store.
     * @throws VerificationException if an attempt was made to spend an already-spent output, or if a transaction incorrectly solved an output script.
     * @throws BlockStoreException if the block store had an underlying error or newBlock does not exist in the block store at all.
     * @return The full set of all changes made to the set of open transaction outputs.
     */
    protected abstract TransactionOutputChanges connectTransactions(StoredBlock newBlock) throws VerificationException, BlockStoreException, PrunedException;    
    
    // Stat counters.
    private long statsLastTime = System.currentTimeMillis();
    private long statsBlocksAdded;

    // filteredTxHashList contains all transactions, filteredTxn just a subset
    private boolean add(Block block, boolean tryConnecting,
                        @Nullable List<Sha256Hash> filteredTxHashList, @Nullable Map<Sha256Hash, Transaction> filteredTxn)
            throws BlockStoreException, VerificationException, PrunedException {
        lock.lock();
        try {
            // TODO: Use read/write locks to ensure that during chain download properties are still low latency.
            if (System.currentTimeMillis() - statsLastTime > 1000) {
                // More than a second passed since last stats logging.
                if (statsBlocksAdded > 1)
                    log.info("{} blocks per second", statsBlocksAdded);
                statsLastTime = System.currentTimeMillis();
                statsBlocksAdded = 0;
            }
            // Quick check for duplicates to avoid an expensive check further down (in findSplit). This can happen a lot
            // when connecting orphan transactions due to the dumb brute force algorithm we use.
            if (block.equals(getChainHead().getHeader())) {
                return true;
            }
            if (tryConnecting && orphanBlocks.containsKey(block.getHash())) {
                return false;
            }

            // If we want to verify transactions (ie we are running with full blocks), verify that block has transactions
            if (shouldVerifyTransactions() && block.transactions == null)
                throw new VerificationException("Got a block header while running in full-block mode");

            // Check for already-seen block, but only for full pruned mode, where the DB is
            // more likely able to handle these queries quickly.
            if (shouldVerifyTransactions() && blockStore.get(block.getHash()) != null) {
                return true;
            }

            // Does this block contain any transactions we might care about? Check this up front before verifying the
            // blocks validity so we can skip the merkle root verification if the contents aren't interesting. This saves
            // a lot of time for big blocks.
            boolean contentsImportant = shouldVerifyTransactions();
            if (block.transactions != null) {
                contentsImportant = contentsImportant || containsRelevantTransactions(block);
            }

            // Prove the block is internally valid: hash is lower than target, etc. This only checks the block contents
            // if there is a tx sending or receiving coins using an address in one of our wallets. And those transactions
            // are only lightly verified: presence in a valid connecting block is taken as proof of validity. See the
            // article here for more details: http://code.google.com/p/bitcoincoinj/wiki/SecurityModel
            try {
                block.verifyHeader();
                if (contentsImportant)
                    block.verifyTransactions();
            } catch (VerificationException e) {
                log.error("Failed to verify block: ", e);
                log.error(block.getHashAsString());
                throw e;
            }

            // Try linking it to a place in the currently known blocks.
            StoredBlock storedPrev = getStoredBlockInCurrentScope(block.getPrevBlockHash());

            if (storedPrev == null) {
                // We can't find the previous block. Probably we are still in the process of downloading the chain and a
                // block was solved whilst we were doing it. We put it to one side and try to connect it later when we
                // have more blocks.
                checkState(tryConnecting, "bug in tryConnectingOrphans");
                log.warn("Block does not connect: {} prev {}", block.getHashAsString(), block.getPrevBlockHash());
                orphanBlocks.put(block.getHash(), new OrphanBlock(block, filteredTxHashList, filteredTxn));
                return false;
            } else {
                // It connects to somewhere on the chain. Not necessarily the top of the best known chain.
                checkDifficultyTransitions(storedPrev, block);
                connectBlock(block, storedPrev, shouldVerifyTransactions(), filteredTxHashList, filteredTxn);
            }

            if (tryConnecting)
                tryConnectingOrphans();

            statsBlocksAdded++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    // expensiveChecks enables checks that require looking at blocks further back in the chain
    // than the previous one when connecting (eg median timestamp check)
    // It could be exposed, but for now we just set it to shouldVerifyTransactions()
    private void connectBlock(final Block block, StoredBlock storedPrev, boolean expensiveChecks,
                              @Nullable final List<Sha256Hash> filteredTxHashList,
                              @Nullable final Map<Sha256Hash, Transaction> filteredTxn) throws BlockStoreException, VerificationException, PrunedException {
        checkState(lock.isHeldByCurrentThread());
        boolean filtered = filteredTxHashList != null && filteredTxn != null;
        // Check that we aren't connecting a block that fails a checkpoint check
        if (!params.passesCheckpoint(storedPrev.getHeight() + 1, block.getHash()))
            throw new VerificationException("Block failed checkpoint lockin at " + (storedPrev.getHeight() + 1));
        if (shouldVerifyTransactions()) {
            checkNotNull(block.transactions);
            for (Transaction tx : block.transactions)
                if (!tx.isFinal(storedPrev.getHeight() + 1, block.getTimeSeconds()))
                   throw new VerificationException("Block contains non-final transaction");
        }
        
        StoredBlock head = getChainHead();
        if (storedPrev.equals(head)) {
            if (filtered && filteredTxn.size() > 0)  {
                log.debug("Block {} connects to top of best chain with {} transaction(s) of which we were sent {}",
                        block.getHashAsString(), filteredTxHashList.size(), filteredTxn.size());
                for (Sha256Hash hash : filteredTxHashList) log.debug("  matched tx {}", hash);
            }
            if (expensiveChecks && block.getTimeSeconds() <= getMedianTimestampOfRecentBlocks(head, blockStore))
                throw new VerificationException("Block's timestamp is too early");
            
            // This block connects to the best known block, it is a normal continuation of the system.
            TransactionOutputChanges txOutChanges = null;
            if (shouldVerifyTransactions())
                txOutChanges = connectTransactions(storedPrev.getHeight() + 1, block);
            StoredBlock newStoredBlock = addToBlockStore(storedPrev,
                    block.transactions == null ? block : block.cloneAsHeader(), txOutChanges);
            setChainHead(newStoredBlock);
            log.debug("Chain is now {} blocks high, running listeners", newStoredBlock.getHeight());
            informListenersForNewBlock(block, NewBlockType.BEST_CHAIN, filteredTxHashList, filteredTxn, newStoredBlock);
        } else {
            // This block connects to somewhere other than the top of the best known chain. We treat these differently.
            //
            // Note that we send the transactions to the wallet FIRST, even if we're about to re-organize this block
            // to become the new best chain head. This simplifies handling of the re-org in the Wallet class.
            StoredBlock newBlock = storedPrev.build(block);
            boolean haveNewBestChain = newBlock.moreWorkThan(head);
            if (haveNewBestChain) {
                log.info("Block is causing a re-organize");
            } else {
                StoredBlock splitPoint = findSplit(newBlock, head, blockStore);
                if (splitPoint != null && splitPoint.equals(newBlock)) {
                    // newStoredBlock is a part of the same chain, there's no fork. This happens when we receive a block
                    // that we already saw and linked into the chain previously, which isn't the chain head.
                    // Re-processing it is confusing for the wallet so just skip.
                    log.warn("Saw duplicated block in main chain at height {}: {}",
                            newBlock.getHeight(), newBlock.getHeader().getHash());
                    return;
                }
                if (splitPoint == null) {
                    // This should absolutely never happen
                    // (lets not write the full block to disk to keep any bugs which allow this to happen
                    //  from writing unreasonable amounts of data to disk)
                    throw new VerificationException("Block forks the chain but splitPoint is null");
                } else {
                    // We aren't actually spending any transactions (yet) because we are on a fork
                    addToBlockStore(storedPrev, block);
                    int splitPointHeight = splitPoint.getHeight();
                    String splitPointHash = splitPoint.getHeader().getHashAsString();
                    log.info("Block forks the chain at height {}/block {}, but it did not cause a reorganize:\n{}",
                            splitPointHeight, splitPointHash, newBlock.getHeader().getHashAsString());
                }
            }
            
            // We may not have any transactions if we received only a header, which can happen during fast catchup.
            // If we do, send them to the wallet but state that they are on a side chain so it knows not to try and
            // spend them until they become activated.
            if (block.transactions != null || filtered) {
                informListenersForNewBlock(block, NewBlockType.SIDE_CHAIN, filteredTxHashList, filteredTxn, newBlock);
            }
            
            if (haveNewBestChain)
                handleNewBestChain(storedPrev, newBlock, block, expensiveChecks);
        }
    }

    private void informListenersForNewBlock(final Block block, final NewBlockType newBlockType,
                                            @Nullable final List<Sha256Hash> filteredTxHashList,
                                            @Nullable final Map<Sha256Hash, Transaction> filteredTxn,
                                            final StoredBlock newStoredBlock) throws VerificationException {
        // Notify the listeners of the new block, so the depth and workDone of stored transactions can be updated
        // (in the case of the listener being a wallet). Wallets need to know how deep each transaction is so
        // coinbases aren't used before maturity.
        boolean first = true;
        Set<Transaction> falsePositives = Sets.newHashSet();
        if (filteredTxn != null) falsePositives.addAll(filteredTxn.values());
        for (final ListenerRegistration<BlockChainListener> registration : listeners) {
            if (registration.executor == Threading.SAME_THREAD) {
                informListenerForNewTransactions(block, newBlockType, filteredTxHashList, filteredTxn,
                        newStoredBlock, first, registration.listener, falsePositives);
                if (newBlockType == NewBlockType.BEST_CHAIN)
                    registration.listener.notifyNewBestBlock(newStoredBlock);
            } else {
                // Listener wants to be run on some other thread, so marshal it across here.
                final boolean notFirst = !first;
                registration.executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // We can't do false-positive handling when executing on another thread
                            Set<Transaction> ignoredFalsePositives = Sets.newHashSet();
                            informListenerForNewTransactions(block, newBlockType, filteredTxHashList, filteredTxn,
                                    newStoredBlock, notFirst, registration.listener, ignoredFalsePositives);
                            if (newBlockType == NewBlockType.BEST_CHAIN)
                                registration.listener.notifyNewBestBlock(newStoredBlock);
                        } catch (VerificationException e) {
                            log.error("Block chain listener threw exception: ", e);
                            // Don't attempt to relay this back to the original peer thread if this was an async
                            // listener invocation.
                            // TODO: Make exception reporting a global feature and use it here.
                        }
                    }
                });
            }
            first = false;
        }

        trackFalsePositives(falsePositives.size());
    }

    private static void informListenerForNewTransactions(Block block, NewBlockType newBlockType,
                                                         @Nullable List<Sha256Hash> filteredTxHashList,
                                                         @Nullable Map<Sha256Hash, Transaction> filteredTxn,
                                                         StoredBlock newStoredBlock, boolean first,
                                                         BlockChainListener listener,
                                                         Set<Transaction> falsePositives) throws VerificationException {
        if (block.transactions != null) {
            // If this is not the first wallet, ask for the transactions to be duplicated before being given
            // to the wallet when relevant. This ensures that if we have two connected wallets and a tx that
            // is relevant to both of them, they don't end up accidentally sharing the same object (which can
            // result in temporary in-memory corruption during re-orgs). See bug 257. We only duplicate in
            // the case of multiple wallets to avoid an unnecessary efficiency hit in the common case.
            sendTransactionsToListener(newStoredBlock, newBlockType, listener, 0, block.transactions,
                    !first, falsePositives);
        } else if (filteredTxHashList != null) {
            checkNotNull(filteredTxn);
            // We must send transactions to listeners in the order they appeared in the block - thus we iterate over the
            // set of hashes and call sendTransactionsToListener with individual txn when they have not already been
            // seen in loose broadcasts - otherwise notifyTransactionIsInBlock on the hash.
            int relativityOffset = 0;
            for (Sha256Hash hash : filteredTxHashList) {
                Transaction tx = filteredTxn.get(hash);
                if (tx != null)
                    sendTransactionsToListener(newStoredBlock, newBlockType, listener, relativityOffset,
                            Arrays.asList(tx), !first, falsePositives);
                else
                    listener.notifyTransactionIsInBlock(hash, newStoredBlock, newBlockType, relativityOffset);
                relativityOffset++;
            }
        }
    }

    /**
     * Gets the median timestamp of the last 11 blocks
     */
    private static long getMedianTimestampOfRecentBlocks(StoredBlock storedBlock,
                                                         BlockStore store) throws BlockStoreException {
        long[] timestamps = new long[11];
        int unused = 9;
        timestamps[10] = storedBlock.getHeader().getTimeSeconds();
        while (unused >= 0 && (storedBlock = storedBlock.getPrev(store)) != null)
            timestamps[unused--] = storedBlock.getHeader().getTimeSeconds();
        
        Arrays.sort(timestamps, unused+1, 11);
        return timestamps[unused + (11-unused)/2];
    }
    
    /**
     * Disconnect each transaction in the block (after reading it from the block store)
     * Only called if(shouldVerifyTransactions())
     * @throws PrunedException if block does not exist as a {@link StoredUndoableBlock} in the block store.
     * @throws BlockStoreException if the block store had an underlying error or block does not exist in the block store at all.
     */
    protected abstract void disconnectTransactions(StoredBlock block) throws PrunedException, BlockStoreException;

    /**
     * Called as part of connecting a block when the new block results in a different chain having higher total work.
     * 
     * if (shouldVerifyTransactions)
     *     Either newChainHead needs to be in the block store as a FullStoredBlock, or (block != null && block.transactions != null)
     */
    private void handleNewBestChain(StoredBlock storedPrev, StoredBlock newChainHead, Block block, boolean expensiveChecks)
            throws BlockStoreException, VerificationException, PrunedException {
        checkState(lock.isHeldByCurrentThread());
        // This chain has overtaken the one we currently believe is best. Reorganize is required.
        //
        // Firstly, calculate the block at which the chain diverged. We only need to examine the
        // chain from beyond this block to find differences.
        StoredBlock head = getChainHead();
        final StoredBlock splitPoint = findSplit(newChainHead, head, blockStore);
        log.info("Re-organize after split at height {}", splitPoint.getHeight());
        log.info("Old chain head: {}", head.getHeader().getHashAsString());
        log.info("New chain head: {}", newChainHead.getHeader().getHashAsString());
        log.info("Split at block: {}", splitPoint.getHeader().getHashAsString());
        // Then build a list of all blocks in the old part of the chain and the new part.
        final LinkedList<StoredBlock> oldBlocks = getPartialChain(head, splitPoint, blockStore);
        final LinkedList<StoredBlock> newBlocks = getPartialChain(newChainHead, splitPoint, blockStore);
        // Disconnect each transaction in the previous main chain that is no longer in the new main chain
        StoredBlock storedNewHead = splitPoint;
        if (shouldVerifyTransactions()) {
            for (StoredBlock oldBlock : oldBlocks) {
                try {
                    disconnectTransactions(oldBlock);
                } catch (PrunedException e) {
                    // We threw away the data we need to re-org this deep! We need to go back to a peer with full
                    // block contents and ask them for the relevant data then rebuild the indexs. Or we could just
                    // give up and ask the human operator to help get us unstuck (eg, rescan from the genesis block).
                    // TODO: Retry adding this block when we get a block with hash e.getHash()
                    throw e;
                }
            }
            StoredBlock cursor;
            // Walk in ascending chronological order.
            for (Iterator<StoredBlock> it = newBlocks.descendingIterator(); it.hasNext();) {
                cursor = it.next();
                if (expensiveChecks && cursor.getHeader().getTimeSeconds() <= getMedianTimestampOfRecentBlocks(cursor.getPrev(blockStore), blockStore))
                    throw new VerificationException("Block's timestamp is too early during reorg");
                TransactionOutputChanges txOutChanges;
                if (cursor != newChainHead || block == null)
                    txOutChanges = connectTransactions(cursor);
                else
                    txOutChanges = connectTransactions(newChainHead.getHeight(), block);
                storedNewHead = addToBlockStore(storedNewHead, cursor.getHeader(), txOutChanges);
            }
        } else {
            // (Finally) write block to block store
            storedNewHead = addToBlockStore(storedPrev, newChainHead.getHeader());
        }
        // Now inform the listeners. This is necessary so the set of currently active transactions (that we can spend)
        // can be updated to take into account the re-organize. We might also have received new coins we didn't have
        // before and our previous spends might have been undone.
        for (final ListenerRegistration<BlockChainListener> registration : listeners) {
            if (registration.executor == Threading.SAME_THREAD) {
                // Short circuit the executor so we can propagate any exceptions.
                // TODO: Do we really need to do this or should it be irrelevant?
                registration.listener.reorganize(splitPoint, oldBlocks, newBlocks);
            } else {
                registration.executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            registration.listener.reorganize(splitPoint, oldBlocks, newBlocks);
                        } catch (VerificationException e) {
                            log.error("Block chain listener threw exception during reorg", e);
                        }
                    }
                });
            }
        }
        // Update the pointer to the best known block.
        setChainHead(storedNewHead);
    }

    /**
     * Returns the set of contiguous blocks between 'higher' and 'lower'. Higher is included, lower is not.
     */
    private static LinkedList<StoredBlock> getPartialChain(StoredBlock higher, StoredBlock lower, BlockStore store) throws BlockStoreException {
        checkArgument(higher.getHeight() > lower.getHeight(), "higher and lower are reversed");
        LinkedList<StoredBlock> results = new LinkedList<StoredBlock>();
        StoredBlock cursor = higher;
        while (true) {
            results.add(cursor);
            cursor = checkNotNull(cursor.getPrev(store), "Ran off the end of the chain");
            if (cursor.equals(lower)) break;
        }
        return results;
    }

    /**
     * Locates the point in the chain at which newStoredBlock and chainHead diverge. Returns null if no split point was
     * found (ie they are not part of the same chain). Returns newChainHead or chainHead if they don't actually diverge
     * but are part of the same chain.
     */
    private static StoredBlock findSplit(StoredBlock newChainHead, StoredBlock oldChainHead,
                                         BlockStore store) throws BlockStoreException {
        StoredBlock currentChainCursor = oldChainHead;
        StoredBlock newChainCursor = newChainHead;
        // Loop until we find the block both chains have in common. Example:
        //
        //    A -> B -> C -> D
        //         \--> E -> F -> G
        //
        // findSplit will return block B. oldChainHead = D and newChainHead = G.
        while (!currentChainCursor.equals(newChainCursor)) {
            if (currentChainCursor.getHeight() > newChainCursor.getHeight()) {
                currentChainCursor = currentChainCursor.getPrev(store);
                checkNotNull(currentChainCursor, "Attempt to follow an orphan chain");
            } else {
                newChainCursor = newChainCursor.getPrev(store);
                checkNotNull(newChainCursor, "Attempt to follow an orphan chain");
            }
        }
        return currentChainCursor;
    }

    /**
     * @return the height of the best known chain, convenience for <tt>getChainHead().getHeight()</tt>.
     */
    public int getBestChainHeight() {
        return getChainHead().getHeight();
    }

    public enum NewBlockType {
        BEST_CHAIN,
        SIDE_CHAIN
    }

    private static void sendTransactionsToListener(StoredBlock block, NewBlockType blockType,
                                                   BlockChainListener listener,
                                                   int relativityOffset,
                                                   List<Transaction> transactions,
                                                   boolean clone,
                                                   Set<Transaction> falsePositives) throws VerificationException {
        for (Transaction tx : transactions) {
            try {
                if (listener.isTransactionRelevant(tx)) {
                    falsePositives.remove(tx);
                    if (clone)
                        tx = new Transaction(tx.params, tx.bitcoinSerialize());
                    listener.receiveFromBlock(tx, block, blockType, relativityOffset++);
                }
            } catch (ScriptException e) {
                // We don't want scripts we don't understand to break the block chain so just note that this tx was
                // not scanned here and continue.
                log.warn("Failed to parse a script: " + e.toString());
            } catch (ProtocolException e) {
                // Failed to duplicate tx, should never happen.
                throw new RuntimeException(e);
            }
        }
    }

    protected void setChainHead(StoredBlock chainHead) throws BlockStoreException {
        doSetChainHead(chainHead);
        synchronized (chainHeadLock) {
            this.chainHead = chainHead;
        }
    }

    /**
     * For each block in orphanBlocks, see if we can now fit it on top of the chain and if so, do so.
     */
    private void tryConnectingOrphans() throws VerificationException, BlockStoreException, PrunedException {
        checkState(lock.isHeldByCurrentThread());
        // For each block in our orphan list, try and fit it onto the head of the chain. If we succeed remove it
        // from the list and keep going. If we changed the head of the list at the end of the round try again until
        // we can't fit anything else on the top.
        //
        // This algorithm is kind of crappy, we should do a topo-sort then just connect them in order, but for small
        // numbers of orphan blocks it does OK.
        int blocksConnectedThisRound;
        do {
            blocksConnectedThisRound = 0;
            Iterator<OrphanBlock> iter = orphanBlocks.values().iterator();
            while (iter.hasNext()) {
                OrphanBlock orphanBlock = iter.next();
                log.debug("Trying to connect {}", orphanBlock.block.getHash());
                // Look up the blocks previous.
                StoredBlock prev = getStoredBlockInCurrentScope(orphanBlock.block.getPrevBlockHash());
                if (prev == null) {
                    // This is still an unconnected/orphan block.
                    log.debug("  but it is not connectable right now");
                    continue;
                }
                // Otherwise we can connect it now.
                // False here ensures we don't recurse infinitely downwards when connecting huge chains.
                add(orphanBlock.block, false, orphanBlock.filteredTxHashes, orphanBlock.filteredTxn);
                iter.remove();
                blocksConnectedThisRound++;
            }
            if (blocksConnectedThisRound > 0) {
                log.info("Connected {} orphan blocks.", blocksConnectedThisRound);
            }
        } while (blocksConnectedThisRound > 0);
    }

    // February 16th 2012
    private static final Date testnetDiffDate = new Date(1329264000000L);

    /**
     * Throws an exception if the blocks difficulty is not correct.
     */
    private void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock) throws BlockStoreException, VerificationException {
        checkState(lock.isHeldByCurrentThread());

        int DiffMode = 1;
        if (params.getId().equals(NetworkParameters.ID_TESTNET)) {
            if (storedPrev.getHeight()+1 >= 16) { DiffMode = 4; }
        }
        else {
            if (storedPrev.getHeight()+1 >= 68589) { DiffMode = 4; }
            else if (storedPrev.getHeight()+1 >= 34140) { DiffMode = 3; }
            else if (storedPrev.getHeight()+1 >= 15200) { DiffMode = 2; }
        }

        if (DiffMode == 1) { checkDifficultyTransitions_V1(storedPrev, nextBlock); return; }
        else if (DiffMode == 2) { checkDifficultyTransitions_V2(storedPrev, nextBlock); return;}
        else if (DiffMode == 3) { DarkGravityWave(storedPrev, nextBlock); return;}
        else if (DiffMode == 4) { DarkGravityWave3(storedPrev, nextBlock); return; }

        DarkGravityWave3(storedPrev, nextBlock);

        return;

    }
    private void DarkGravityWave1(StoredBlock storedPrev, Block nextBlock) {
    /* current difficulty formula, limecoin - DarkGravity, written by Evan Duffield - evan@limecoin.io */
        StoredBlock BlockLastSolved = storedPrev;
        StoredBlock BlockReading = storedPrev;
        Block BlockCreating = nextBlock;
        //BlockCreating = BlockCreating;
        long nBlockTimeAverage = 0;
        long nBlockTimeAveragePrev = 0;
        long nBlockTimeCount = 0;
        long nBlockTimeSum2 = 0;
        long nBlockTimeCount2 = 0;
        long LastBlockTime = 0;
        long PastBlocksMin = 14;
        long PastBlocksMax = 140;
        long CountBlocks = 0;
        BigInteger PastDifficultyAverage = BigInteger.valueOf(0);
        BigInteger PastDifficultyAveragePrev = BigInteger.valueOf(0);

        //if (BlockLastSolved == NULL || BlockLastSolved->nHeight == 0 || BlockLastSolved->nHeight < PastBlocksMin) { return bnProofOfWorkLimit.GetCompact(); }
        if (BlockLastSolved == null || BlockLastSolved.getHeight() == 0 || (long)BlockLastSolved.getHeight() < PastBlocksMin)
        { verifyDifficulty(params.getProofOfWorkLimit(), storedPrev, nextBlock); }

        for (int i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
            if (PastBlocksMax > 0 && i > PastBlocksMax) { break; }
            CountBlocks++;

            if(CountBlocks <= PastBlocksMin) {
                if (CountBlocks == 1) { PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger(); }
                else
                {
                    //PastDifficultyAverage = ((CBigNum().SetCompact(BlockReading->nBits) - PastDifficultyAveragePrev) / CountBlocks) + PastDifficultyAveragePrev;
                    PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger().subtract(PastDifficultyAveragePrev).divide(BigInteger.valueOf(CountBlocks)).add(PastDifficultyAveragePrev);

                }
                PastDifficultyAveragePrev = PastDifficultyAverage;
            }

            if(LastBlockTime > 0){
                long Diff = (LastBlockTime - BlockReading.getHeader().getTimeSeconds());
                if(Diff < 0) Diff = 0;
                if(nBlockTimeCount <= PastBlocksMin) {
                    nBlockTimeCount++;

                    if (nBlockTimeCount == 1) { nBlockTimeAverage = Diff; }
                    else { nBlockTimeAverage = ((Diff - nBlockTimeAveragePrev) / nBlockTimeCount) + nBlockTimeAveragePrev; }
                    nBlockTimeAveragePrev = nBlockTimeAverage;
                }
                nBlockTimeCount2++;
                nBlockTimeSum2 += Diff;
            }
            LastBlockTime = BlockReading.getHeader().getTimeSeconds();

            //if (BlockReading->pprev == NULL)
            try {
                StoredBlock BlockReadingPrev = blockStore.get(BlockReading.getHeader().getPrevBlockHash());
                if (BlockReadingPrev == null)
                {
                    //assert(BlockReading); break;
                    return;
                }
                BlockReading = BlockReadingPrev;
            }
            catch(BlockStoreException x)
            {
                return;
            }
        }

        BigInteger bnNew = PastDifficultyAverage;
        if (nBlockTimeCount != 0 && nBlockTimeCount2 != 0) {
            double SmartAverage = (((nBlockTimeAverage)*0.7)+((nBlockTimeSum2 / nBlockTimeCount2)*0.3));
            if(SmartAverage < 1) SmartAverage = 1;
            double Shift = CoinDefinition.TARGET_SPACING/SmartAverage;

            long nActualTimespan = (long)((CountBlocks*CoinDefinition.TARGET_SPACING)/Shift);
            long nTargetTimespan = (CountBlocks*CoinDefinition.TARGET_SPACING);
            if (nActualTimespan < nTargetTimespan/3)
                nActualTimespan = nTargetTimespan/3;
            if (nActualTimespan > nTargetTimespan*3)
                nActualTimespan = nTargetTimespan*3;

            // Retarget
            bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
            bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespan));
        }
        verifyDifficulty(bnNew, storedPrev, nextBlock);

        /*if (bnNew > bnProofOfWorkLimit){
            bnNew = bnProofOfWorkLimit;
        }

        return bnNew.GetCompact();*/
    }
    private void DarkGravityWave(StoredBlock storedPrev, Block nextBlock) {
    /* current difficulty formula, limecoin - DarkGravity, written by Evan Duffield - evan@limecoin.io */
        StoredBlock BlockLastSolved = storedPrev;
        StoredBlock BlockReading = storedPrev;
        Block BlockCreating = nextBlock;
        //BlockCreating = BlockCreating;
        long nBlockTimeAverage = 0;
        long nBlockTimeAveragePrev = 0;
        long nBlockTimeCount = 0;
        long nBlockTimeSum2 = 0;
        long nBlockTimeCount2 = 0;
        long LastBlockTime = 0;
        long PastBlocksMin = 14;
        long PastBlocksMax = 140;
        long CountBlocks = 0;
        BigInteger PastDifficultyAverage = BigInteger.valueOf(0);
        BigInteger PastDifficultyAveragePrev = BigInteger.valueOf(0);

        //if (BlockLastSolved == NULL || BlockLastSolved->nHeight == 0 || BlockLastSolved->nHeight < PastBlocksMin) { return bnProofOfWorkLimit.GetCompact(); }
        if (BlockLastSolved == null || BlockLastSolved.getHeight() == 0 || (long)BlockLastSolved.getHeight() < PastBlocksMin)
        { verifyDifficulty(params.getProofOfWorkLimit(), storedPrev, nextBlock); }

        for (int i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
            if (PastBlocksMax > 0 && i > PastBlocksMax)
            {
                break;
            }
            CountBlocks++;

            if(CountBlocks <= PastBlocksMin) {
                if (CountBlocks == 1) { PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger(); }
                else
                {
                    //PastDifficultyAverage = ((CBigNum().SetCompact(BlockReading->nBits) - PastDifficultyAveragePrev) / CountBlocks) + PastDifficultyAveragePrev;
                    PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger().subtract(PastDifficultyAveragePrev).divide(BigInteger.valueOf(CountBlocks)).add(PastDifficultyAveragePrev);

                }
                PastDifficultyAveragePrev = PastDifficultyAverage;
            }

            if(LastBlockTime > 0){
                long Diff = (LastBlockTime - BlockReading.getHeader().getTimeSeconds());
                //if(Diff < 0)
                //   Diff = 0;
                if(nBlockTimeCount <= PastBlocksMin) {
                    nBlockTimeCount++;

                    if (nBlockTimeCount == 1) { nBlockTimeAverage = Diff; }
                    else { nBlockTimeAverage = ((Diff - nBlockTimeAveragePrev) / nBlockTimeCount) + nBlockTimeAveragePrev; }
                    nBlockTimeAveragePrev = nBlockTimeAverage;
                }
                nBlockTimeCount2++;
                nBlockTimeSum2 += Diff;
            }
            LastBlockTime = BlockReading.getHeader().getTimeSeconds();

            //if (BlockReading->pprev == NULL)
            try {
                StoredBlock BlockReadingPrev = blockStore.get(BlockReading.getHeader().getPrevBlockHash());
                if (BlockReadingPrev == null)
                {
                    //assert(BlockReading); break;
                    return;
                }
                BlockReading = BlockReadingPrev;
            }
            catch(BlockStoreException x)
            {
                return;
            }
        }

        BigInteger bnNew = PastDifficultyAverage;
        if (nBlockTimeCount != 0 && nBlockTimeCount2 != 0) {
            double SmartAverage = ((((double)nBlockTimeAverage)*0.7)+(((double)nBlockTimeSum2 / (double)nBlockTimeCount2)*0.3));
            if(SmartAverage < 1) SmartAverage = 1;
            double Shift = CoinDefinition.TARGET_SPACING/SmartAverage;

            double fActualTimespan = (((double)CountBlocks*(double)CoinDefinition.TARGET_SPACING)/Shift);
            double fTargetTimespan = ((double)CountBlocks*CoinDefinition.TARGET_SPACING);
            if (fActualTimespan < fTargetTimespan/3)
                fActualTimespan = fTargetTimespan/3;
            if (fActualTimespan > fTargetTimespan*3)
                fActualTimespan = fTargetTimespan*3;

            long nActualTimespan = (long)fActualTimespan;
            long nTargetTimespan = (long)fTargetTimespan;

            // Retarget
            bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
            bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespan));
        }
        verifyDifficulty(bnNew, storedPrev, nextBlock);

        /*if (bnNew > bnProofOfWorkLimit){
            bnNew = bnProofOfWorkLimit;
        }

        return bnNew.GetCompact();*/
    }
    private void DarkGravityWave3(StoredBlock storedPrev, Block nextBlock) {
        /* current difficulty formula, bit - DarkGravity v3, written by Evan Duffield - evan@bit.io */
        StoredBlock BlockLastSolved = storedPrev;
        StoredBlock BlockReading = storedPrev;
        Block BlockCreating = nextBlock;
        BlockCreating = BlockCreating;
        long nActualTimespan = 0;
        long LastBlockTime = 0;
        long PastBlocksMin = 24;
        long PastBlocksMax = 24;
        long CountBlocks = 0;
        BigInteger PastDifficultyAverage = BigInteger.ZERO;
        BigInteger PastDifficultyAveragePrev = BigInteger.ZERO;

        if (BlockLastSolved == null || BlockLastSolved.getHeight() == 0 || BlockLastSolved.getHeight() < PastBlocksMin) {
            verifyDifficulty(params.getProofOfWorkLimit(), storedPrev, nextBlock);
            return;
        }

        for (int i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
            if (PastBlocksMax > 0 && i > PastBlocksMax) { break; }
            CountBlocks++;

            if(CountBlocks <= PastBlocksMin) {
                if (CountBlocks == 1) { PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger(); }
                else { PastDifficultyAverage = ((PastDifficultyAveragePrev.multiply(BigInteger.valueOf(CountBlocks)).add(BlockReading.getHeader().getDifficultyTargetAsInteger()).divide(BigInteger.valueOf(CountBlocks + 1)))); }
                PastDifficultyAveragePrev = PastDifficultyAverage;
            }

            if(LastBlockTime > 0){
                long Diff = (LastBlockTime - BlockReading.getHeader().getTimeSeconds());
                nActualTimespan += Diff;
            }
            LastBlockTime = BlockReading.getHeader().getTimeSeconds();

            try {
                StoredBlock BlockReadingPrev = blockStore.get(BlockReading.getHeader().getPrevBlockHash());
                if (BlockReadingPrev == null)
                {
                    //assert(BlockReading); break;
                    return;
                }
                BlockReading = BlockReadingPrev;
            }
            catch(BlockStoreException x)
            {
                return;
            }
        }

        BigInteger bnNew= PastDifficultyAverage;

        long nTargetTimespan = CountBlocks*params.TARGET_SPACING;//nTargetSpacing;

        if (nActualTimespan < nTargetTimespan/3)
            nActualTimespan = nTargetTimespan/3;
        if (nActualTimespan > nTargetTimespan*3)
            nActualTimespan = nTargetTimespan*3;

        // Retarget
        bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
        bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespan));
        verifyDifficulty(bnNew, storedPrev, nextBlock);
        
    }


    private void checkDifficultyTransitions_V1(StoredBlock storedPrev, Block nextBlock) throws BlockStoreException, VerificationException {
        //checkState(lock.isHeldByCurrentThread());
        Block prev = storedPrev.getHeader();

        // Is this supposed to be a difficulty transition point?
        if ((storedPrev.getHeight() + 1) % params.getInterval() != 0) {

            // TODO: Refactor this hack after 0.5 is released and we stop supporting deserialization compatibility.
            // This should be a method of the NetworkParameters, which should in turn be using singletons and a subclass
            // for each network type. Then each network can define its own difficulty transition rules.
            if (params.getId().equals(NetworkParameters.ID_TESTNET) && nextBlock.getTime().after(testnetDiffDate)) {
                checkTestnetDifficulty(storedPrev, prev, nextBlock);
                return;
            }

            // No ... so check the difficulty didn't actually change.
            if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                        ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                        Long.toHexString(prev.getDifficultyTarget()));
            return;
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
        // two weeks after the initial block chain download.
        long now = System.currentTimeMillis();
        StoredBlock cursor = blockStore.get(prev.getHash());

        int blockstogoback = params.getInterval() - 1;
        if(storedPrev.getHeight()+1 != params.getInterval())
            blockstogoback = params.getInterval();

        for (int i = 0; i < blockstogoback; i++) {
            if (cursor == null) {
                // This should never happen. If it does, it means we are following an incorrect or busted chain.
                throw new VerificationException(
                        "Difficulty transition point but we did not find a way back to the genesis block.");
            }
            cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
        }
        long elapsed = System.currentTimeMillis() - now;
        if (elapsed > 50)
            log.info("Difficulty transition traversal took {}msec", elapsed);

        Block blockIntervalAgo = cursor.getHeader();
        int timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
        // Limit the adjustment step.
        final int targetTimespan = params.getTargetTimespan();
        if (timespan < targetTimespan / 4)
            timespan = targetTimespan / 4;
        if (timespan > targetTimespan * 4)
            timespan = targetTimespan * 4;

        BigInteger newDifficulty = Utils.decodeCompactBits(prev.getDifficultyTarget());
        newDifficulty = newDifficulty.multiply(BigInteger.valueOf(timespan));
        newDifficulty = newDifficulty.divide(BigInteger.valueOf(targetTimespan));

        if (newDifficulty.compareTo(params.getProofOfWorkLimit()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newDifficulty.toString(16));
            newDifficulty = params.getProofOfWorkLimit();
        }

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        BigInteger receivedDifficulty = nextBlock.getDifficultyTargetAsInteger();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newDifficulty = newDifficulty.and(mask);

        if (newDifficulty.compareTo(receivedDifficulty) != 0)
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                    receivedDifficulty.toString(16) + " vs " + newDifficulty.toString(16));
    }

    private void checkDifficultyTransitions_V2(StoredBlock storedPrev, Block nextBlock) throws BlockStoreException, VerificationException {
        final long      	BlocksTargetSpacing			= (long)(2.5 * 60); // 10 minutes
        int         		TimeDaySeconds				= 60 * 60 * 24;
        long				PastSecondsMin				= TimeDaySeconds / 40;
        long				PastSecondsMax				= TimeDaySeconds * 7;
        long				PastBlocksMin				= PastSecondsMin / BlocksTargetSpacing;   //? blocks
        long				PastBlocksMax				= PastSecondsMax / BlocksTargetSpacing;   //? blocks

        //if(!fgw.isNativeLibraryLoaded())
            //long start = System.currentTimeMillis();
            KimotoGravityWell(storedPrev, nextBlock, BlocksTargetSpacing, PastBlocksMin, PastBlocksMax);
            //long end1 = System.currentTimeMillis();
            //if(kgw.isNativeLibraryLoaded())
        //else
            //    KimotoGravityWell_N(storedPrev, nextBlock, BlocksTargetSpacing, PastBlocksMin, PastBlocksMax);
            //long end2 = System.currentTimeMillis();
            //if(kgw.isNativeLibraryLoaded())
        //    KimotoGravityWell_N2(storedPrev, nextBlock, BlocksTargetSpacing, PastBlocksMin, PastBlocksMax);
        /*long end3 = System.currentTimeMillis();

        long java = end1 - start;
        long n1 = end2 - end1;
        long n2 = end3 - end2;
        if(i > 20)
        {
            j += java;
            N += n1;
            N2 += n2;
            if(i != 0 && ((i % 10) == 0))
             //log.info("KGW 10 blocks: J={}; N={} -%.0f%; N2={} -%.0f%", java, n1, ((double)(java-n1))/java*100, n2, ((double)(java-n2))/java*100);
                 log.info("KGW {} blocks: J={}; N={} -{}%; N2={} -{}%", i-20, j, N, ((double)(j-N))/j*100, N2, ((double)(j-N2))/j*100);
        }
        ++i;*/
    }

    private void KimotoGravityWell(StoredBlock storedPrev, Block nextBlock, long TargetBlocksSpacingSeconds, long PastBlocksMin, long PastBlocksMax)  throws BlockStoreException, VerificationException {
	/* current difficulty formula, megacoin - kimoto gravity well */
        //const CBlockIndex  *BlockLastSolved				= pindexLast;
        //const CBlockIndex  *BlockReading				= pindexLast;
        //const CBlockHeader *BlockCreating				= pblock;
        StoredBlock         BlockLastSolved             = storedPrev;
        StoredBlock         BlockReading                = storedPrev;
        Block               BlockCreating               = nextBlock;

        BlockCreating				= BlockCreating;
        long				PastBlocksMass				= 0;
        long				PastRateActualSeconds		= 0;
        long				PastRateTargetSeconds		= 0;
        double				PastRateAdjustmentRatio		= 1f;
        BigInteger			PastDifficultyAverage = BigInteger.valueOf(0);
        BigInteger			PastDifficultyAveragePrev = BigInteger.valueOf(0);;
        double				EventHorizonDeviation;
        double				EventHorizonDeviationFast;
        double				EventHorizonDeviationSlow;

        long start = System.currentTimeMillis();

        if (BlockLastSolved == null || BlockLastSolved.getHeight() == 0 || (long)BlockLastSolved.getHeight() < PastBlocksMin)
        { verifyDifficulty(params.getProofOfWorkLimit(), storedPrev, nextBlock); }

        int i = 0;
        long LatestBlockTime = BlockLastSolved.getHeader().getTimeSeconds();

        for (i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
            if (PastBlocksMax > 0 && i > PastBlocksMax) { break; }
            PastBlocksMass++;

            if (i == 1)	{ PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger(); }
            else		{ PastDifficultyAverage = ((BlockReading.getHeader().getDifficultyTargetAsInteger().subtract(PastDifficultyAveragePrev)).divide(BigInteger.valueOf(i)).add(PastDifficultyAveragePrev)); }
            PastDifficultyAveragePrev = PastDifficultyAverage;


            if (BlockReading.getHeight() > 646120 && LatestBlockTime < BlockReading.getHeader().getTimeSeconds()) {
                //eliminates the ability to go back in time
                LatestBlockTime = BlockReading.getHeader().getTimeSeconds();
            }

            PastRateActualSeconds			= BlockLastSolved.getHeader().getTimeSeconds() - BlockReading.getHeader().getTimeSeconds();
            PastRateTargetSeconds			= TargetBlocksSpacingSeconds * PastBlocksMass;
            PastRateAdjustmentRatio			= 1.0f;
            if (BlockReading.getHeight() > 646120){
                //this should slow down the upward difficulty change
                if (PastRateActualSeconds < 5) { PastRateActualSeconds = 5; }
            }
            else {
                if (PastRateActualSeconds < 0) { PastRateActualSeconds = 0; }
            }
            if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
                PastRateAdjustmentRatio			= (double)PastRateTargetSeconds / PastRateActualSeconds;
            }
            EventHorizonDeviation			= 1 + (0.7084 * java.lang.Math.pow((Double.valueOf(PastBlocksMass)/Double.valueOf(28.2)), -1.228));
            EventHorizonDeviationFast		= EventHorizonDeviation;
            EventHorizonDeviationSlow		= 1 / EventHorizonDeviation;

            if (PastBlocksMass >= PastBlocksMin) {
                if ((PastRateAdjustmentRatio <= EventHorizonDeviationSlow) || (PastRateAdjustmentRatio >= EventHorizonDeviationFast))
                {
                    /*assert(BlockReading)*/;
                    break;
                }
            }
            StoredBlock BlockReadingPrev = blockStore.get(BlockReading.getHeader().getPrevBlockHash());
            if (BlockReadingPrev == null)
            {
                //assert(BlockReading);
                //Since we are using the checkpoint system, there may not be enough blocks to do this diff adjust, so skip until we do
                //break;
                return;
            }
            BlockReading = BlockReadingPrev;
        }

        /*CBigNum bnNew(PastDifficultyAverage);
        if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
            bnNew *= PastRateActualSeconds;
            bnNew /= PastRateTargetSeconds;
        } */
        //log.info("KGW-J, {}, {}, {}", storedPrev.getHeight(), i, System.currentTimeMillis() - start);
        BigInteger newDifficulty = PastDifficultyAverage;
        if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
            newDifficulty = newDifficulty.multiply(BigInteger.valueOf(PastRateActualSeconds));
            newDifficulty = newDifficulty.divide(BigInteger.valueOf(PastRateTargetSeconds));
        }

        if (newDifficulty.compareTo(params.getProofOfWorkLimit()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newDifficulty.toString(16));
            newDifficulty = params.getProofOfWorkLimit();
        }


        //log.info("KGW-j Difficulty Calculated: {}", newDifficulty.toString(16));
        verifyDifficulty(newDifficulty, storedPrev, nextBlock);

    }

    static double ConvertBitsToDouble(long nBits){
        long nShift = (nBits >> 24) & 0xff;

        double dDiff =
                (double)0x0000ffff / (double)(nBits & 0x00ffffff);

        while (nShift < 29)
        {
            dDiff *= 256.0;
            nShift++;
        }
        while (nShift > 29)
        {
            dDiff /= 256.0;
            nShift--;
        }

        return dDiff;
    }

    private void verifyDifficulty(BigInteger calcDiff, StoredBlock storedPrev, Block nextBlock)
    {
        if (calcDiff.compareTo(params.getProofOfWorkLimit()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", calcDiff.toString(16));
            calcDiff = params.getProofOfWorkLimit();
        }
        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        BigInteger receivedDifficulty = nextBlock.getDifficultyTargetAsInteger();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        calcDiff = calcDiff.and(mask);
        if(params.getId().compareTo(params.ID_TESTNET) == 0)
        {
            if (calcDiff.compareTo(receivedDifficulty) != 0)
                throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                        receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
        }
        else
        {



            int height = storedPrev.getHeight() + 1;
            ///if(System.getProperty("os.name").toLowerCase().contains("windows"))
            //{
            if(height <= 68589)
            {
                long nBitsNext = nextBlock.getDifficultyTarget();

                long calcDiffBits = (accuracyBytes+3) << 24;
                calcDiffBits |= calcDiff.shiftRight(accuracyBytes*8).longValue();

                double n1 = ConvertBitsToDouble(calcDiffBits);
                double n2 = ConvertBitsToDouble(nBitsNext);




                if(java.lang.Math.abs(n1-n2) > n1*0.2)
                              throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));


            }
            else
            {
                    if (calcDiff.compareTo(receivedDifficulty) != 0)
                        throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
            }



            /*
            if(height >= 34140)
                {
                    long nBitsNext = nextBlock.getDifficultyTarget();

                    long calcDiffBits = (accuracyBytes+3) << 24;
                    calcDiffBits |= calcDiff.shiftRight(accuracyBytes*8).longValue();

                    double n1 = ConvertBitsToDouble(calcDiffBits);
                    double n2 = ConvertBitsToDouble(nBitsNext);

                    if(height <= 45000) {


                        if(java.lang.Math.abs(n1-n2) > n1*0.2)
                            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                    receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));


                    }
                    else if(java.lang.Math.abs(n1-n2) > n1*0.005)
                        throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));

                }
                else
                {
                    if (calcDiff.compareTo(receivedDifficulty) != 0)
                        throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
                }
            */

            //}
            /*else
            {

            if(height >= 34140 && height <= 45000)
            {
                long nBitsNext = nextBlock.getDifficultyTarget();

                long calcDiffBits = (accuracyBytes+3) << 24;
                calcDiffBits |= calcDiff.shiftRight(accuracyBytes*8).longValue();

                double n1 = ConvertBitsToDouble(calcDiffBits);
                double n2 = ConvertBitsToDouble(nBitsNext);

                if(java.lang.Math.abs(n1-n2) > n1*0.2)
                    throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                            receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));

            }
            else
            {
                if (calcDiff.compareTo(receivedDifficulty) != 0)
                    throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                            receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
            }

            }*/
        }
    }

    private void checkTestnetDifficulty(StoredBlock storedPrev, Block prev, Block next) throws VerificationException, BlockStoreException {
        checkState(lock.isHeldByCurrentThread());
        // After 15th February 2012 the rules on the testnet change to avoid people running up the difficulty
        // and then leaving, making it too hard to mine a block. On non-difficulty transition points, easy
        // blocks are allowed if there has been a span of 20 minutes without one.
        final long timeDelta = next.getTimeSeconds() - prev.getTimeSeconds();
        // There is an integer underflow bug in bitcoin-qt that means mindiff blocks are accepted when time
        // goes backwards.
        if (timeDelta >= 0 && timeDelta <= NetworkParameters.TARGET_SPACING * 2) {
            // Walk backwards until we find a block that doesn't have the easiest proof of work, then check
            // that difficulty is equal to that one.
            StoredBlock cursor = storedPrev;
            while (!cursor.getHeader().equals(params.getGenesisBlock()) &&
                   cursor.getHeight() % params.getInterval() != 0 &&
                   cursor.getHeader().getDifficultyTargetAsInteger().equals(params.getProofOfWorkLimit()))
                cursor = cursor.getPrev(blockStore);
            BigInteger cursorDifficulty = cursor.getHeader().getDifficultyTargetAsInteger();
            BigInteger newDifficulty = next.getDifficultyTargetAsInteger();
            if (!cursorDifficulty.equals(newDifficulty))
                throw new VerificationException("Testnet block transition that is not allowed: " +
                    Long.toHexString(cursor.getHeader().getDifficultyTarget()) + " vs " +
                    Long.toHexString(next.getDifficultyTarget()));
        }
    }

    /**
     * Returns true if any connected wallet considers any transaction in the block to be relevant.
     */
    private boolean containsRelevantTransactions(Block block) {
        // Does not need to be locked.
        for (Transaction tx : block.transactions) {
            try {
                for (final ListenerRegistration<BlockChainListener> registration : listeners) {
                    if (registration.executor != Threading.SAME_THREAD) continue;
                    if (registration.listener.isTransactionRelevant(tx)) return true;
                }
            } catch (ScriptException e) {
                // We don't want scripts we don't understand to break the block chain so just note that this tx was
                // not scanned here and continue.
                log.warn("Failed to parse a script: " + e.toString());
            }
        }
        return false;
    }

    /**
     * Returns the block at the head of the current best chain. This is the block which represents the greatest
     * amount of cumulative work done.
     */
    public StoredBlock getChainHead() {
        synchronized (chainHeadLock) {
            return chainHead;
        }
    }

    /**
     * An orphan block is one that does not connect to the chain anywhere (ie we can't find its parent, therefore
     * it's an orphan). Typically this occurs when we are downloading the chain and didn't reach the head yet, and/or
     * if a block is solved whilst we are downloading. It's possible that we see a small amount of orphan blocks which
     * chain together, this method tries walking backwards through the known orphan blocks to find the bottom-most.
     *
     * @return from or one of froms parents, or null if "from" does not identify an orphan block
     */
    @Nullable
    public Block getOrphanRoot(Sha256Hash from) {
        lock.lock();
        try {
            OrphanBlock cursor = orphanBlocks.get(from);
            if (cursor == null)
                return null;
            OrphanBlock tmp;
            while ((tmp = orphanBlocks.get(cursor.block.getPrevBlockHash())) != null) {
                cursor = tmp;
            }
            return cursor.block;
        } finally {
            lock.unlock();
        }
    }

    /** Returns true if the given block is currently in the orphan blocks list. */
    public boolean isOrphan(Sha256Hash block) {
        lock.lock();
        try {
            return orphanBlocks.containsKey(block);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an estimate of when the given block will be reached, assuming a perfect 10 minute average for each
     * block. This is useful for turning transaction lock times into human readable times. Note that a height in
     * the past will still be estimated, even though the time of solving is actually known (we won't scan backwards
     * through the chain to obtain the right answer).
     */
    public Date estimateBlockTime(int height) {
        synchronized (chainHeadLock) {
            long offset = height - chainHead.getHeight();
            long headTime = chainHead.getHeader().getTimeSeconds();
            long estimated = (headTime * 1000) + (1000L * 60L * 10L * offset);
            return new Date(estimated);
        }
    }

    /**
     * Returns a future that completes when the block chain has reached the given height. Yields the
     * {@link StoredBlock} of the block that reaches that height first. The future completes on a peer thread.
     */
    public ListenableFuture<StoredBlock> getHeightFuture(final int height) {
        final SettableFuture<StoredBlock> result = SettableFuture.create();
        addListener(new AbstractBlockChainListener() {
            @Override
            public void notifyNewBestBlock(StoredBlock block) throws VerificationException {
                if (block.getHeight() >= height) {
                    removeListener(this);
                    result.set(block);
                }
            }
        }, Threading.SAME_THREAD);
        return result;
    }



    /**
     * The false positive rate is the average over all blockchain transactions of:
     *
     * - 1.0 if the transaction was false-positive (was irrelevant to all listeners)
     * - 0.0 if the transaction was relevant or filtered out
     */
    public double getFalsePositiveRate() {
        return falsePositiveRate;
    }

    /*
     * We completed handling of a filtered block. Update false-positive estimate based
     * on the total number of transactions in the original block.
     *
     * count includes filtered transactions, transactions that were passed in and were relevant
     * and transactions that were false positives (i.e. includes all transactions in the block).
     */
    protected void trackFilteredTransactions(int count) {
        // Track non-false-positives in batch.  Each non-false-positive counts as
        // 0.0 towards the estimate.
        //
        // This is slightly off because we are applying false positive tracking before non-FP tracking,
        // which counts FP as if they came at the beginning of the block.  Assuming uniform FP
        // spread in a block, this will somewhat underestimate the FP rate (5% for 1000 tx block).
        double alphaDecay = Math.pow(1 - FP_ESTIMATOR_ALPHA, count);

        // new_rate = alpha_decay * new_rate
        falsePositiveRate = alphaDecay * falsePositiveRate;

        double betaDecay = Math.pow(1 - FP_ESTIMATOR_BETA, count);

        // trend = beta * (new_rate - old_rate) + beta_decay * trend
        falsePositiveTrend =
                FP_ESTIMATOR_BETA * count * (falsePositiveRate - previousFalsePositiveRate) +
                betaDecay * falsePositiveTrend;

        // new_rate += alpha_decay * trend
        falsePositiveRate += alphaDecay * falsePositiveTrend;

        // Stash new_rate in old_rate
        previousFalsePositiveRate = falsePositiveRate;
    }

    /* Irrelevant transactions were received.  Update false-positive estimate. */
    void trackFalsePositives(int count) {
        // Track false positives in batch by adding alpha to the false positive estimate once per count.
        // Each false positive counts as 1.0 towards the estimate.
        falsePositiveRate += FP_ESTIMATOR_ALPHA * count;
        if (count > 0)
            log.debug("{} false positives, current rate = {} trend = {}", count, falsePositiveRate, falsePositiveTrend);
    }

    /** Resets estimates of false positives. Used when the filter is sent to the peer. */
    public void resetFalsePositiveEstimate() {
        falsePositiveRate = 0;
        falsePositiveTrend = 0;
        previousFalsePositiveRate = 0;
    }
}
