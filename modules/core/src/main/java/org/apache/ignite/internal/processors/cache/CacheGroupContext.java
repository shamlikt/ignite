/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cache.affinity.AffinityFunction;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataPageEvictionMode;
import org.apache.ignite.configuration.TopologyValidator;
import org.apache.ignite.events.CacheRebalancingEvent;
import org.apache.ignite.internal.IgniteClientDisconnectedCheckedException;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.affinity.AffinityAssignment;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.affinity.GridAffinityAssignmentCache;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtAffinityAssignmentRequest;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtAffinityAssignmentResponse;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPreloader;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtLocalPartition;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtPartitionState;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtPartitionTopology;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtPartitionTopologyImpl;
import org.apache.ignite.internal.processors.cache.persistence.DataRegion;
import org.apache.ignite.internal.processors.cache.persistence.GridCacheOffheapManager;
import org.apache.ignite.internal.processors.cache.persistence.freelist.FreeList;
import org.apache.ignite.internal.processors.cache.persistence.pagemem.PageMemoryEx;
import org.apache.ignite.internal.processors.cache.persistence.partstate.GroupPartitionId;
import org.apache.ignite.internal.processors.cache.persistence.partstate.PartitionRecoverState;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.PagePartitionMetaIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.reuse.ReuseList;
import org.apache.ignite.internal.processors.cache.query.continuous.CounterSkipContext;
import org.apache.ignite.internal.processors.query.QueryUtils;
import org.apache.ignite.internal.util.typedef.CI1;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.LT;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiInClosure;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.mxbean.CacheGroupMetricsMXBean;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.cache.CacheAtomicityMode.TRANSACTIONAL_SNAPSHOT;
import static org.apache.ignite.cache.CacheMode.LOCAL;
import static org.apache.ignite.cache.CacheMode.REPLICATED;
import static org.apache.ignite.cache.CacheRebalanceMode.NONE;
import static org.apache.ignite.events.EventType.EVT_CACHE_REBALANCE_PART_UNLOADED;
import static org.apache.ignite.internal.managers.communication.GridIoPolicy.AFFINITY_POOL;

/**
 *
 */
public class CacheGroupContext {
    /**
     * Unique group ID. Currently for shared group it is generated as group name hash,
     * for non-shared as cache name hash (see {@link ClusterCachesInfo#checkCacheConflict}).
     */
    private final int grpId;

    /** Node ID cache group was received from. */
    private volatile UUID rcvdFrom;

    /** Flag indicating that this cache group is in a recovery mode due to partitions loss. */
    private boolean needsRecovery;

    /** */
    private volatile AffinityTopologyVersion locStartVer;

    /** */
    private final CacheConfiguration<?, ?> ccfg;

    /** */
    private final GridCacheSharedContext ctx;

    /** */
    private volatile boolean affNode;

    /** */
    private final CacheType cacheType;

    /** */
    private final byte ioPlc;

    /** */
    private final boolean depEnabled;

    /** */
    private final boolean storeCacheId;

    /** */
    private volatile List<GridCacheContext> caches;

    /** */
    private volatile List<GridCacheContext> contQryCaches;

    /** */
    private final IgniteLogger log;

    /** */
    private volatile GridAffinityAssignmentCache aff;

    /** */
    private volatile GridDhtPartitionTopologyImpl top;

    /** */
    private volatile IgniteCacheOffheapManager offheapMgr;

    /** */
    private volatile GridCachePreloader preldr;

    /** */
    private final DataRegion dataRegion;

    /** Persistence enabled flag. */
    private final boolean persistenceEnabled;

    /** */
    private final CacheObjectContext cacheObjCtx;

    /** */
    private final FreeList freeList;

    /** */
    private final ReuseList reuseList;

    /** */
    private boolean drEnabled;

    /** */
    private boolean qryEnabled;

    /** */
    private boolean mvccEnabled;

    /** MXBean. */
    private CacheGroupMetricsMXBean mxBean;

    /** */
    private volatile boolean localWalEnabled;

    /** */
    private volatile boolean globalWalEnabled;

    /** Flag indicates that cache group is under recovering and not attached to topology. */
    private final AtomicBoolean recoveryMode;

    /** Flag indicates that all group partitions have restored their state from page memory / disk. */
    private volatile boolean partitionStatesRestored;

    /**
     * @param ctx Context.
     * @param grpId Group ID.
     * @param rcvdFrom Node ID cache group was received from.
     * @param cacheType Cache type.
     * @param ccfg Cache configuration.
     * @param affNode Affinity node flag.
     * @param dataRegion data region.
     * @param cacheObjCtx Cache object context.
     * @param freeList Free list.
     * @param reuseList Reuse list.
     * @param locStartVer Topology version when group was started on local node.
     * @param persistenceEnabled Persistence enabled flag.
     * @param walEnabled Wal enabled flag.
     */
    CacheGroupContext(
        GridCacheSharedContext ctx,
        int grpId,
        UUID rcvdFrom,
        CacheType cacheType,
        CacheConfiguration ccfg,
        boolean affNode,
        DataRegion dataRegion,
        CacheObjectContext cacheObjCtx,
        FreeList freeList,
        ReuseList reuseList,
        AffinityTopologyVersion locStartVer,
        boolean persistenceEnabled,
        boolean walEnabled,
        boolean recoveryMode
    ) {
        assert ccfg != null;
        assert dataRegion != null || !affNode;
        assert grpId != 0 : "Invalid group ID [cache=" + ccfg.getName() + ", grpName=" + ccfg.getGroupName() + ']';

        this.grpId = grpId;
        this.rcvdFrom = rcvdFrom;
        this.ctx = ctx;
        this.ccfg = ccfg;
        this.affNode = affNode;
        this.dataRegion = dataRegion;
        this.cacheObjCtx = cacheObjCtx;
        this.freeList = freeList;
        this.reuseList = reuseList;
        this.locStartVer = locStartVer;
        this.cacheType = cacheType;
        this.globalWalEnabled = walEnabled;
        this.persistenceEnabled = persistenceEnabled;
        this.localWalEnabled = true;
        this.recoveryMode = new AtomicBoolean(recoveryMode);

        ioPlc = cacheType.ioPolicy();

        depEnabled = ctx.kernalContext().deploy().enabled() && !ctx.kernalContext().cacheObjects().isBinaryEnabled(ccfg);

        storeCacheId = affNode && dataRegion.config().getPageEvictionMode() != DataPageEvictionMode.DISABLED;

        mvccEnabled = ccfg.getAtomicityMode() == TRANSACTIONAL_SNAPSHOT;

        log = ctx.kernalContext().log(getClass());

        caches = new ArrayList<>();

        mxBean = new CacheGroupMetricsMXBeanImpl(this);
    }

    /**
     * @return Mvcc flag.
     */
    public boolean mvccEnabled() {
        return mvccEnabled;
    }

    /**
     * @return {@code True} if this is cache group for one of system caches.
     */
    public boolean systemCache() {
        return !sharedGroup() && CU.isSystemCache(ccfg.getName());
    }

    /**
     * @return Node ID initiated cache group start.
     */
    public UUID receivedFrom() {
        return rcvdFrom;
    }

    /**
     * @return {@code True} if cacheId should be stored in data pages.
     */
    public boolean storeCacheIdInDataPage() {
        return storeCacheId;
    }

    /**
     * @return {@code True} if deployment is enabled.
     */
    public boolean deploymentEnabled() {
        return depEnabled;
    }

    /**
     * @return Preloader.
     */
    public GridCachePreloader preloader() {
        return preldr;
    }

    /**
     * @return IO policy for the given cache group.
     */
    public byte ioPolicy() {
        return ioPlc;
    }

    /**
     * @param cctx Cache context.
     * @throws IgniteCheckedException If failed.
     */
    void onCacheStarted(GridCacheContext cctx) throws IgniteCheckedException {
        addCacheContext(cctx);

        offheapMgr.onCacheStarted(cctx);
    }

    /**
     * @param cacheName Cache name.
     * @return {@code True} if group contains cache with given name.
     */
    public boolean hasCache(String cacheName) {
        List<GridCacheContext> caches = this.caches;

        for (int i = 0; i < caches.size(); i++) {
            if (caches.get(i).name().equals(cacheName))
                return true;
        }

        return false;
    }

    /**
     * @param cctx Cache context.
     */
    private void addCacheContext(GridCacheContext cctx) {
        assert cacheType.userCache() == cctx.userCache() : cctx.name();
        assert grpId == cctx.groupId() : cctx.name();

        ArrayList<GridCacheContext> caches = new ArrayList<>(this.caches);

        assert sharedGroup() || caches.isEmpty();

        boolean add = caches.add(cctx);

        assert add : cctx.name();

        if (!qryEnabled && QueryUtils.isEnabled(cctx.config()))
            qryEnabled = true;

        if (!drEnabled && cctx.isDrEnabled())
            drEnabled = true;

        this.caches = caches;
    }

    /**
     * @param cctx Cache context.
     */
    private void removeCacheContext(GridCacheContext cctx) {
        ArrayList<GridCacheContext> caches = new ArrayList<>(this.caches);

        // It is possible cache was not added in case of errors on cache start.
        for (Iterator<GridCacheContext> it = caches.iterator(); it.hasNext();) {
            GridCacheContext next = it.next();

            if (next == cctx) {
                assert sharedGroup() || caches.size() == 1 : caches.size();

                it.remove();

                break;
            }
        }

        if (QueryUtils.isEnabled(cctx.config())) {
            boolean qryEnabled = false;

            for (int i = 0; i < caches.size(); i++) {
                if (QueryUtils.isEnabled(caches.get(i).config())) {
                    qryEnabled = true;

                    break;
                }
            }

            this.qryEnabled = qryEnabled;
        }

        if (cctx.isDrEnabled()) {
            boolean drEnabled = false;

            for (int i = 0; i < caches.size(); i++) {
                if (caches.get(i).isDrEnabled()) {
                    drEnabled = true;

                    break;
                }
            }

            this.drEnabled = drEnabled;
        }

        this.caches = caches;
    }

    /**
     * @return Cache context if group contains single cache.
     */
    public GridCacheContext singleCacheContext() {
        List<GridCacheContext> caches = this.caches;

        assert !sharedGroup() && caches.size() == 1 :
            "stopping=" + ctx.kernalContext().isStopping() + ", groupName=" + ccfg.getGroupName() +
                ", caches=" + caches;

        return caches.get(0);
    }

    /**
     *
     */
    public void unwindUndeploys() {
        List<GridCacheContext> caches = this.caches;

        for (int i = 0; i < caches.size(); i++) {
            GridCacheContext cctx = caches.get(i);

            cctx.deploy().unwind(cctx);
        }
    }

    /**
     * @param type Event type to check.
     * @return {@code True} if given event type should be recorded.
     */
    public boolean eventRecordable(int type) {
        return cacheType.userCache() && ctx.gridEvents().isRecordable(type);
    }

    /**
     * @return {@code True} if cache created by user.
     */
    public boolean userCache() {
        return cacheType.userCache();
    }

    /**
     * Adds rebalancing event.
     *
     * @param part Partition.
     * @param type Event type.
     * @param discoNode Discovery node.
     * @param discoType Discovery event type.
     * @param discoTs Discovery event timestamp.
     */
    public void addRebalanceEvent(int part, int type, ClusterNode discoNode, int discoType, long discoTs) {
        assert discoNode != null;
        assert type > 0;
        assert discoType > 0;
        assert discoTs > 0;

        if (!eventRecordable(type))
            LT.warn(log, "Added event without checking if event is recordable: " + U.gridEventName(type));

        List<GridCacheContext> caches = this.caches;

        for (int i = 0; i < caches.size(); i++) {
            GridCacheContext cctx = caches.get(i);

            if (!cctx.config().isEventsDisabled() && cctx.recordEvent(type)) {
                cctx.gridEvents().record(new CacheRebalancingEvent(cctx.name(),
                    cctx.localNode(),
                    "Cache rebalancing event.",
                    type,
                    part,
                    discoNode,
                    discoType,
                    discoTs));
            }
        }
    }

    /**
     * Adds partition unload event.
     *
     * @param part Partition.
     */
    public void addUnloadEvent(int part) {
        if (!eventRecordable(EVT_CACHE_REBALANCE_PART_UNLOADED))
            LT.warn(log, "Added event without checking if event is recordable: " +
                U.gridEventName(EVT_CACHE_REBALANCE_PART_UNLOADED));

        List<GridCacheContext> caches = this.caches;

        for (int i = 0; i < caches.size(); i++) {
            GridCacheContext cctx = caches.get(i);

            if (!cctx.config().isEventsDisabled())
                cctx.gridEvents().record(new CacheRebalancingEvent(cctx.name(),
                    cctx.localNode(),
                    "Cache unloading event.",
                    EVT_CACHE_REBALANCE_PART_UNLOADED,
                    part,
                    null,
                    0,
                    0));
        }
    }

    /**
     * @param part Partition.
     * @param key Key.
     * @param evtNodeId Event node ID.
     * @param type Event type.
     * @param newVal New value.
     * @param hasNewVal Has new value flag.
     * @param oldVal Old values.
     * @param hasOldVal Has old value flag.
     * @param keepBinary Keep binary flag.
     */
    public void addCacheEvent(
        int part,
        KeyCacheObject key,
        UUID evtNodeId,
        int type,
        @Nullable CacheObject newVal,
        boolean hasNewVal,
        @Nullable CacheObject oldVal,
        boolean hasOldVal,
        boolean keepBinary
    ) {
        List<GridCacheContext> caches = this.caches;

        for (int i = 0; i < caches.size(); i++) {
            GridCacheContext cctx = caches.get(i);

            if (!cctx.config().isEventsDisabled())
                cctx.events().addEvent(part,
                    key,
                    evtNodeId,
                    (IgniteUuid)null,
                    null,
                    type,
                    newVal,
                    hasNewVal,
                    oldVal,
                    hasOldVal,
                    null,
                    null,
                    null,
                    keepBinary);
        }
    }

    /**
     * @return {@code True} if contains cache with query indexing enabled.
     */
    public boolean queriesEnabled() {
        return qryEnabled;
    }

    /**
     * @return {@code True} in case replication is enabled.
     */
    public boolean isDrEnabled() {
        return drEnabled;
    }

    /**
     * @return Free List.
     */
    public FreeList freeList() {
        return freeList;
    }

    /**
     * @return Reuse List.
     */
    public ReuseList reuseList() {
        return reuseList;
    }

    /**
     * @return Cache object context.
     */
    public CacheObjectContext cacheObjectContext() {
        return cacheObjCtx;
    }

    /**
     * @return Cache shared context.
     */
    public GridCacheSharedContext shared() {
        return ctx;
    }

    /**
     * @return data region.
     */
    public DataRegion dataRegion() {
        return dataRegion;
    }

    /**
     * @return {@code True} if local node is affinity node.
     */
    public boolean affinityNode() {
        return affNode;
    }

    /**
     * @return Topology.
     */
    public GridDhtPartitionTopology topology() {
        if (top == null)
            throw new IllegalStateException("Topology is not initialized: " + cacheOrGroupName());

        return top;
    }

    /**
     * @return {@code True} if current thread holds lock on topology.
     */
    public boolean isTopologyLocked() {
        if (top == null)
            return false;

        return top.holdsLock();
    }

    /**
     * @return Offheap manager.
     */
    public IgniteCacheOffheapManager offheap() {
        return offheapMgr;
    }

    /**
     * @return Current cache state. Must only be modified during exchange.
     */
    public boolean needsRecovery() {
        return needsRecovery;
    }

    /**
     * @param needsRecovery Needs recovery flag.
     */
    public void needsRecovery(boolean needsRecovery) {
        this.needsRecovery = needsRecovery;
    }

    /**
     * @return Topology version when group was started on local node.
     */
    public AffinityTopologyVersion localStartVersion() {
        return locStartVer;
    }

    /**
     * @return {@code True} if cache is local.
     */
    public boolean isLocal() {
        return ccfg.getCacheMode() == LOCAL;
    }

    /**
     * @return {@code True} if cache is local.
     */
    public boolean isReplicated() {
        return ccfg.getCacheMode() == REPLICATED;
    }

    /**
     * @return Cache configuration.
     */
    public CacheConfiguration config() {
        return ccfg;
    }

    /**
     * @return Cache node filter.
     */
    public IgnitePredicate<ClusterNode> nodeFilter() {
        return ccfg.getNodeFilter();
    }

    /**
     * @return Configured user objects which should be initialized/stopped on group start/stop.
     */
    Collection<?> configuredUserObjects() {
        return Arrays.asList(ccfg.getAffinity(), ccfg.getNodeFilter(), ccfg.getTopologyValidator());
    }

    /**
     * @return Configured topology validator.
     */
    @Nullable public TopologyValidator topologyValidator() {
        return ccfg.getTopologyValidator();
    }

    /**
     * @return Configured affinity function.
     */
    public AffinityFunction affinityFunction() {
        return ccfg.getAffinity();
    }

    /**
     * @return Affinity.
     */
    public GridAffinityAssignmentCache affinity() {
        return aff;
    }

    /**
     * @return Group name or {@code null} if group name was not specified for cache.
     */
    @Nullable public String name() {
        return ccfg.getGroupName();
    }

    /**
     * @return Group name if it is specified, otherwise cache name.
     */
    public String cacheOrGroupName() {
        return ccfg.getGroupName() != null ? ccfg.getGroupName() : ccfg.getName();
    }

    /**
     * @return Group ID.
     */
    public int groupId() {
        return grpId;
    }

    /**
     * @return {@code True} if group can contain multiple caches.
     */
    public boolean sharedGroup() {
        return ccfg.getGroupName() != null;
    }

    /**
     *
     */
    public void onKernalStop() {
        if (!isRecoveryMode()) {
            aff.cancelFutures(new IgniteCheckedException("Failed to wait for topology update, node is stopping."));

            preldr.onKernalStop();
        }

        offheapMgr.onKernalStop();
    }

    /**
     * @param cctx Cache context.
     * @param destroy Destroy data flag. Setting to <code>true</code> will remove all cache data.
     */
    void stopCache(GridCacheContext cctx, boolean destroy) {
        if (top != null)
            top.onCacheStopped(cctx.cacheId());

        offheapMgr.stopCache(cctx.cacheId(), destroy);

        removeCacheContext(cctx);
    }

    /**
     *
     */
    void stopGroup() {
        offheapMgr.stop();

        if (isRecoveryMode())
            return;

        IgniteCheckedException err =
            new IgniteCheckedException("Failed to wait for topology update, cache (or node) is stopping.");

        ctx.evict().onCacheGroupStopped(this);

        aff.cancelFutures(err);

        preldr.onKernalStop();

        ctx.io().removeCacheGroupHandlers(grpId);
    }

    /**
     * Finishes recovery for current cache group.
     * Attaches topology version and initializes I/O.
     *
     * @param startVer Cache group start version.
     * @param originalReceivedFrom UUID of node that was first who initiated cache group creating.
     *                             This is needed to decide should node calculate affinity locally or fetch from other nodes.
     * @param affinityNode Flag indicates, is local node affinity node or not. This may be calculated only after node joined to topology.
     * @throws IgniteCheckedException If failed.
     */
    public void finishRecovery(
        AffinityTopologyVersion startVer,
        UUID originalReceivedFrom,
        boolean affinityNode
    ) throws IgniteCheckedException {
        if (recoveryMode.compareAndSet(true, false)) {
            affNode = affinityNode;

            rcvdFrom = originalReceivedFrom;

            locStartVer = startVer;

            persistGlobalWalState(globalWalEnabled);

            initializeIO();

            ctx.affinity().onCacheGroupCreated(this);
        }
    }

    /**
     * Pre-create partitions that resides in page memory or WAL and restores their state.
     */
    public long restorePartitionStates(Map<GroupPartitionId, PartitionRecoverState> partitionRecoveryStates) throws IgniteCheckedException {
        if (isLocal() || !affinityNode() || !dataRegion().config().isPersistenceEnabled())
            return 0;

        if (partitionStatesRestored)
            return 0;

        long processed = 0;

        PageMemoryEx pageMem = (PageMemoryEx)dataRegion().pageMemory();

        for (int p = 0; p < affinity().partitions(); p++) {
            PartitionRecoverState recoverState = partitionRecoveryStates.get(new GroupPartitionId(grpId, p));

            if (ctx.pageStore().exists(grpId, p)) {
                ctx.pageStore().ensure(grpId, p);

                if (ctx.pageStore().pages(grpId, p) <= 1) {
                    if (log.isDebugEnabled())
                        log.debug("Skipping partition on recovery (pages less than 1) " +
                            "[grp=" + cacheOrGroupName() + ", p=" + p + "]");

                    continue;
                }

                if (log.isDebugEnabled())
                    log.debug("Creating partition on recovery (exists in page store) " +
                        "[grp=" + cacheOrGroupName() + ", p=" + p + "]");

                processed++;

                GridDhtLocalPartition part = topology().forceCreatePartition(p);

                offheap().onPartitionInitialCounterUpdated(p, 0);

                ctx.database().checkpointReadLock();

                try {
                    long partMetaId = pageMem.partitionMetaPageId(grpId, p);
                    long partMetaPage = pageMem.acquirePage(grpId, partMetaId);

                    try {
                        long pageAddr = pageMem.writeLock(grpId, partMetaId, partMetaPage);

                        boolean changed = false;

                        try {
                            PagePartitionMetaIO io = PagePartitionMetaIO.VERSIONS.forPage(pageAddr);

                            if (recoverState != null) {
                                io.setPartitionState(pageAddr, (byte) recoverState.stateId());

                                changed = updateState(part, recoverState.stateId());

                                if (recoverState.stateId() == GridDhtPartitionState.OWNING.ordinal()
                                    || (recoverState.stateId() == GridDhtPartitionState.MOVING.ordinal()
                                    && part.initialUpdateCounter() < recoverState.updateCounter())) {
                                    part.initialUpdateCounter(recoverState.updateCounter());

                                    changed = true;
                                }

                                if (log.isInfoEnabled())
                                    log.warning("Restored partition state (from WAL) " +
                                        "[grp=" + cacheOrGroupName() + ", p=" + p + ", state=" + part.state() +
                                        ", updCntr=" + part.initialUpdateCounter() + "]");
                            }
                            else {
                                int stateId = (int) io.getPartitionState(pageAddr);

                                changed = updateState(part, stateId);

                                if (log.isDebugEnabled())
                                    log.debug("Restored partition state (from page memory) " +
                                        "[grp=" + cacheOrGroupName() + ", p=" + p + ", state=" + part.state() +
                                        ", updCntr=" + part.initialUpdateCounter() + ", stateId=" + stateId + "]");
                            }
                        }
                        finally {
                            pageMem.writeUnlock(grpId, partMetaId, partMetaPage, null, changed);
                        }
                    }
                    finally {
                        pageMem.releasePage(grpId, partMetaId, partMetaPage);
                    }
                }
                finally {
                    ctx.database().checkpointReadUnlock();
                }
            }
            else if (recoverState != null) {
                GridDhtLocalPartition part = topology().forceCreatePartition(p);

                offheap().onPartitionInitialCounterUpdated(p, recoverState.updateCounter());

                updateState(part, recoverState.stateId());

                processed++;

                if (log.isDebugEnabled())
                    log.debug("Restored partition state (from WAL) " +
                        "[grp=" + cacheOrGroupName() + ", p=" + p + ", state=" + part.state() +
                        ", updCntr=" + part.initialUpdateCounter() + "]");
            }
            else {
                if (log.isDebugEnabled())
                    log.debug("Skipping partition on recovery (no page store OR wal state) " +
                        "[grp=" + cacheOrGroupName() + ", p=" + p + "]");
            }
        }

        partitionStatesRestored = true;

        return processed;
    }

    /**
     * @param part Partition to restore state for.
     * @param stateId State enum ordinal.
     * @return Updated flag.
     */
    private boolean updateState(GridDhtLocalPartition part, int stateId) {
        if (stateId != -1) {
            GridDhtPartitionState state = GridDhtPartitionState.fromOrdinal(stateId);

            assert state != null;

            part.restoreState(state == GridDhtPartitionState.EVICTED ? GridDhtPartitionState.RENTING : state);

            return true;
        }

        return false;
    }

    /**
     * @return {@code True} if current cache group is in recovery mode.
     */
    public boolean isRecoveryMode() {
        return recoveryMode.get();
    }

    /**
     * Initializes affinity and rebalance I/O handlers.
     */
    private void initializeIO() throws IgniteCheckedException {
        assert !recoveryMode.get() : "Couldn't initialize I/O handlers, recovery mode is on for group " + this;

        if (ccfg.getCacheMode() != LOCAL) {
            if (!ctx.kernalContext().clientNode()) {
                ctx.io().addCacheGroupHandler(groupId(), GridDhtAffinityAssignmentRequest.class,
                    (IgniteBiInClosure<UUID, GridDhtAffinityAssignmentRequest>) this::processAffinityAssignmentRequest);
            }

            preldr = new GridDhtPreloader(this);

            preldr.start();
        }
        else
            preldr = new GridCachePreloaderAdapter(this);
    }

    /**
     * @return IDs of caches in this group.
     */
    public Set<Integer> cacheIds() {
        List<GridCacheContext> caches = this.caches;

        Set<Integer> ids = U.newHashSet(caches.size());

        for (int i = 0; i < caches.size(); i++)
            ids.add(caches.get(i).cacheId());

        return ids;
    }

    /**
     * @return Caches in this group.
     */
    public List<GridCacheContext> caches() {
        return this.caches;
    }

    /**
     * @return {@code True} if group contains caches.
     */
    public boolean hasCaches() {
        List<GridCacheContext> caches = this.caches;

        return !caches.isEmpty();
    }

    /**
     * @param part Partition ID.
     */
    public void onPartitionEvicted(int part) {
        List<GridCacheContext> caches = this.caches;

        for (int i = 0; i < caches.size(); i++) {
            GridCacheContext cctx = caches.get(i);

            if (cctx.isDrEnabled())
                cctx.dr().partitionEvicted(part);

            cctx.continuousQueries().onPartitionEvicted(part);
        }
    }

    /**
     * @param cctx Cache context.
     */
    public void addCacheWithContinuousQuery(GridCacheContext cctx) {
        assert sharedGroup() : cacheOrGroupName();
        assert cctx.group() == this : cctx.name();
        assert !cctx.isLocal() : cctx.name();

        synchronized (this) {
            List<GridCacheContext> contQryCaches = this.contQryCaches;

            if (contQryCaches == null)
                contQryCaches = new ArrayList<>();

            contQryCaches.add(cctx);

            this.contQryCaches = contQryCaches;
        }
    }

    /**
     * @param cctx Cache context.
     */
    public void removeCacheWithContinuousQuery(GridCacheContext cctx) {
        assert sharedGroup() : cacheOrGroupName();
        assert cctx.group() == this : cctx.name();
        assert !cctx.isLocal() : cctx.name();

        synchronized (this) {
            List<GridCacheContext> contQryCaches = this.contQryCaches;

            if (contQryCaches == null)
                return;

            contQryCaches.remove(cctx);

            if (contQryCaches.isEmpty())
                contQryCaches = null;

            this.contQryCaches = contQryCaches;
        }
    }

    /**
     * @param cacheId ID of cache initiated counter update.
     * @param part Partition number.
     * @param cntr Counter.
     * @param topVer Topology version for current operation.
     */
    public void onPartitionCounterUpdate(int cacheId,
        int part,
        long cntr,
        AffinityTopologyVersion topVer,
        boolean primary) {
        assert sharedGroup();

        if (isLocal())
            return;

        List<GridCacheContext> contQryCaches = this.contQryCaches;

        if (contQryCaches == null)
            return;

        CounterSkipContext skipCtx = null;

        for (int i = 0; i < contQryCaches.size(); i++) {
            GridCacheContext cctx = contQryCaches.get(i);

            if (cacheId != cctx.cacheId())
                skipCtx = cctx.continuousQueries().skipUpdateCounter(skipCtx, part, cntr, topVer, primary);
        }

        final List<Runnable> procC = skipCtx != null ? skipCtx.processClosures() : null;

        if (procC != null) {
            ctx.kernalContext().closure().runLocalSafe(new Runnable() {
                @Override public void run() {
                    for (Runnable c : procC)
                        c.run();
                }
            });
        }
    }

    /**
     * @return {@code True} if there is at least one cache with registered CQ exists in this group.
     */
    public boolean hasContinuousQueryCaches() {
        return !F.isEmpty(contQryCaches);
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    public void start() throws IgniteCheckedException {
        aff = new GridAffinityAssignmentCache(ctx.kernalContext(),
            cacheOrGroupName(),
            grpId,
            ccfg.getAffinity(),
            ccfg.getNodeFilter(),
            ccfg.getBackups(),
            ccfg.getCacheMode() == LOCAL,
            persistenceEnabled());

        if (ccfg.getCacheMode() != LOCAL)
            top = new GridDhtPartitionTopologyImpl(ctx, this);

        try {
            offheapMgr = persistenceEnabled
                ? new GridCacheOffheapManager()
                : new IgniteCacheOffheapManagerImpl();
        }
        catch (Exception e) {
            throw new IgniteCheckedException("Failed to initialize offheap manager", e);
        }

        offheapMgr.start(ctx, this);

        if (!isRecoveryMode()) {
            initializeIO();

            ctx.affinity().onCacheGroupCreated(this);
        }
    }

    /**
     * @return Persistence enabled flag.
     */
    public boolean persistenceEnabled() {
        return persistenceEnabled;
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    private void processAffinityAssignmentRequest(UUID nodeId, GridDhtAffinityAssignmentRequest req) {
        if (log.isDebugEnabled())
            log.debug("Processing affinity assignment request [node=" + nodeId + ", req=" + req + ']');

        IgniteInternalFuture<AffinityTopologyVersion> fut = aff.readyFuture(req.topologyVersion());

        if (fut != null) {
            fut.listen(new CI1<IgniteInternalFuture<AffinityTopologyVersion>>() {
                @Override public void apply(IgniteInternalFuture<AffinityTopologyVersion> fut) {
                    processAffinityAssignmentRequest0(nodeId, req);
                }
            });
        }
        else
            processAffinityAssignmentRequest0(nodeId, req);
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    private void processAffinityAssignmentRequest0(UUID nodeId, final GridDhtAffinityAssignmentRequest req) {
        AffinityTopologyVersion topVer = req.topologyVersion();

        if (log.isDebugEnabled())
            log.debug("Affinity is ready for topology version, will send response [topVer=" + topVer +
                ", node=" + nodeId + ']');

        AffinityAssignment assignment = aff.cachedAffinity(topVer);

        GridDhtAffinityAssignmentResponse res = new GridDhtAffinityAssignmentResponse(
            req.futureId(),
            grpId,
            topVer,
            assignment.assignment());

        if (aff.centralizedAffinityFunction()) {
            assert assignment.idealAssignment() != null;

            res.idealAffinityAssignment(assignment.idealAssignment());
        }

        if (req.sendPartitionsState())
            res.partitionMap(top.partitionMap(true));

        try {
            ctx.io().send(nodeId, res, AFFINITY_POOL);
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send affinity assignment response to remote node [node=" + nodeId + ']', e);
        }
    }

    /**
     * @param reconnectFut Reconnect future.
     */
    public void onDisconnected(IgniteFuture reconnectFut) {
        IgniteCheckedException err = new IgniteClientDisconnectedCheckedException(reconnectFut,
            "Failed to wait for topology update, client disconnected.");

        if (aff != null)
            aff.cancelFutures(err);
    }

    /**
     * @return {@code True} if rebalance is enabled.
     */
    public boolean rebalanceEnabled() {
        return ccfg.getRebalanceMode() != NONE;
    }

    /**
     *
     */
    public void onReconnected() {
        aff.onReconnected();

        if (top != null)
            top.onReconnected();

        preldr.onReconnected();
    }

    /**
     * @return MXBean.
     */
    public CacheGroupMetricsMXBean mxBean() {
        return mxBean;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return "CacheGroupContext [grp=" + cacheOrGroupName() + ']';
    }

    /**
     * WAL enabled flag.
     */
    public boolean walEnabled() {
        return localWalEnabled && globalWalEnabled;
    }

    /**
     * Local WAL enabled flag.
     */
    public boolean localWalEnabled() {
        return localWalEnabled;
    }

    /**
     * @return Global WAL enabled flag.
     */
    public boolean globalWalEnabled() {
        return globalWalEnabled;
    }

    /**
     * @param enabled Global WAL enabled flag.
     */
    public void globalWalEnabled(boolean enabled) {
        if (globalWalEnabled != enabled) {
            log.info("Global WAL state for group=" + cacheOrGroupName() +
                " changed from " + globalWalEnabled + " to " + enabled);

            persistGlobalWalState(enabled);

            globalWalEnabled = enabled;
        }
    }

    /**
     * @param enabled Local WAL enabled flag.
     */
    public void localWalEnabled(boolean enabled) {
        if (localWalEnabled != enabled){
            log.info("Local WAL state for group=" + cacheOrGroupName() +
                " changed from " + localWalEnabled + " to " + enabled);

            persistLocalWalState(enabled);

            localWalEnabled = enabled;
        }
    }

    /**
     * @param enabled Enabled flag..
     */
    private void persistGlobalWalState(boolean enabled) {
        shared().database().walEnabled(grpId, enabled, false);
    }

    /**
     * @param enabled Enabled flag..
     */
    private void persistLocalWalState(boolean enabled) {
        shared().database().walEnabled(grpId, enabled, true);
    }
}
