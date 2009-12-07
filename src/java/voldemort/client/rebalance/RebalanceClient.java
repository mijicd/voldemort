package voldemort.client.rebalance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.client.protocol.admin.AdminClient;
import voldemort.client.protocol.admin.ProtoBuffAdminClientRequestFormat;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.store.rebalancing.RedirectingStore;
import voldemort.utils.RebalanceUtils;
import voldemort.versioning.VectorClock;

public class RebalanceClient {

    private static Logger logger = Logger.getLogger(RebalanceClient.class);

    private final ExecutorService executor;
    private final AdminClient adminClient;
    private final RebalanceClientConfig config;

    public RebalanceClient(String bootstrapUrl, RebalanceClientConfig config) {
        this.adminClient = new ProtoBuffAdminClientRequestFormat(bootstrapUrl, config);
        this.executor = Executors.newFixedThreadPool(config.getMaxParallelRebalancingNodes());
        this.config = config;
    }

    public RebalanceClient(Cluster cluster, RebalanceClientConfig config) {
        this.adminClient = new ProtoBuffAdminClientRequestFormat(cluster, config);
        this.executor = Executors.newFixedThreadPool(config.getMaxParallelRebalancingNodes());
        this.config = config;
    }

    /**
     * Voldemort dynamic cluster membership rebalancing mechanism. <br>
     * Migrate partitions across nodes to managed changes in cluster
     * memberships. <br>
     * Takes two cluster configuration currentCluster and targetCluster as
     * parameters compares and makes a list of partitions need to be
     * transferred.<br>
     * The cluster is kept consistent during rebalancing using a proxy mechanism
     * via {@link RedirectingStore}<br>
     * 
     * 
     * @param storeName : store to be rebalanced
     * @param currentCluster: currentCluster configuration.
     * @param targetCluster: target Cluster configuration
     */
    public void rebalance(final String storeName,
                          final Cluster currentCluster,
                          final Cluster targetCluster) {
        // update adminClient with currentCluster
        adminClient.setCluster(currentCluster);

        if(!RebalanceUtils.getClusterRebalancingToken()) {
            throw new VoldemortException("Failed to get Cluster permission to rebalance sleep and retry ...");
        }

        final Map<Integer, List<RebalanceStealInfo>> stealPartitionsMap = RebalanceUtils.getStealPartitionsMap(storeName,
                                                                                                               currentCluster,
                                                                                                               targetCluster);
        logger.info("Rebalancing plan:\n"
                    + RebalanceUtils.getStealPartitionsMapAsString(stealPartitionsMap));

        final Map<Integer, AtomicBoolean> nodeRebalancingLock = createRebalancingLocks(stealPartitionsMap);
        final Semaphore semaphore = new Semaphore(config.getMaxParallelRebalancingNodes());

        while(!stealPartitionsMap.isEmpty()) {
            this.executor.execute(new Runnable() {

                public void run() {
                    if(acquireSemaphore(semaphore)) {
                        try {
                            // get one target stealer(destination) node
                            int stealerNodeId = RebalanceUtils.getRandomStealerNodeId(stealPartitionsMap);

                            if(nodeRebalancingLock.get(stealerNodeId).compareAndSet(false, true)) {
                                RebalanceStealInfo rebalanceStealInfo = RebalanceUtils.getOneStealInfoAndUpdateStealMap(stealerNodeId,
                                                                                                                        stealPartitionsMap);
                                if(rebalanceCommitOrRevert(stealPartitionsMap,
                                                           stealerNodeId,
                                                           rebalanceStealInfo)) {
                                    boolean success = attemptRebalanceTransfer(stealerNodeId,
                                                                               rebalanceStealInfo);

                                    if(!success) {
                                        if(rebalanceStealInfo.getAttempt() < config.getMaxRebalancingAttempt()) {
                                            // increment attempt and add back.
                                            rebalanceStealInfo.setAttempt(rebalanceStealInfo.getAttempt() + 1);
                                            RebalanceUtils.revertStealPartitionsMap(stealPartitionsMap,
                                                                                    stealerNodeId,
                                                                                    rebalanceStealInfo);
                                        } else {
                                            logger.error("Rebalance attempt for node:"
                                                         + stealerNodeId + " failed max times.");
                                        }

                                    }
                                }

                                // free rebalancing lock for this node.
                                nodeRebalancingLock.get(stealerNodeId).set(false);
                            }
                        } catch(Exception e) {
                            logger.warn("Rebalance step failed", e);
                        } finally {
                            semaphore.release();
                            logger.debug("rebalancing semaphore released.");
                        }
                    } else {
                        logger.warn(new VoldemortException("Failed to get rebalance task permit."));
                    }
                }

                /**
                 * Does an atomic commit or revert for the intended partitions
                 * ownership changes.<br>
                 * creates a new cluster metadata by moving partitions list
                 * passed in parameter rebalanceStealInfo and propagates it to
                 * all nodes.<br>
                 * Revert all changes if failed to copy on required copies
                 * (stealerNode and donorNode).<br>
                 * holds a lock untill the commit/revert finishes.
                 * 
                 * @param stealPartitionsMap
                 * @param stealerNodeId
                 * @param rebalanceStealInfo
                 * @return
                 */
                private boolean rebalanceCommitOrRevert(Map<Integer, List<RebalanceStealInfo>> stealPartitionsMap,
                                                        int stealerNodeId,
                                                        RebalanceStealInfo rebalanceStealInfo) {
                    synchronized(stealPartitionsMap) {
                        VectorClock clock = (VectorClock) adminClient.getRemoteCluster(rebalanceStealInfo.getDonorId())
                                                                     .getVersion();
                        Cluster oldCluster = adminClient.getCluster();

                        try {
                            // update cluster.xml and tell all Nodes
                            Cluster newCluster = RebalanceUtils.createUpdatedCluster(oldCluster,
                                                                                     getStealerNode(currentCluster,
                                                                                                    targetCluster,
                                                                                                    stealerNodeId),
                                                                                     currentCluster.getNodeById(rebalanceStealInfo.getDonorId()),
                                                                                     rebalanceStealInfo.getPartitionList());
                            // increment clock version on stealerNodeId
                            clock.incrementVersion(stealerNodeId, System.currentTimeMillis());
                            RebalanceUtils.propagateCluster(adminClient,
                                                            newCluster,
                                                            clock,
                                                            Arrays.asList(stealerNodeId,
                                                                          rebalanceStealInfo.getDonorId()));

                            // set new cluster in adminClient
                            adminClient.setCluster(newCluster);
                            return true;
                        } catch(Exception e) {
                            logger.warn("Failed to commit rebalance on node:" + stealerNodeId, e);
                            // revert stealPartitions changes
                            RebalanceUtils.revertStealPartitionsMap(stealPartitionsMap,
                                                                    stealerNodeId,
                                                                    rebalanceStealInfo);
                            // revert cluster changes.
                            clock.incrementVersion(stealerNodeId, System.currentTimeMillis());
                            RebalanceUtils.propagateCluster(adminClient,
                                                            oldCluster,
                                                            clock,
                                                            new ArrayList<Integer>());

                        }

                        return false;
                    }
                }

                private Node getStealerNode(Cluster currentCluster,
                                            Cluster targetCluster,
                                            int stealerNodeId) {
                    if(RebalanceUtils.containsNode(currentCluster, stealerNodeId))
                        return currentCluster.getNodeById(stealerNodeId);
                    else
                        return RebalanceUtils.updateNode(targetCluster.getNodeById(stealerNodeId),
                                                         new ArrayList<Integer>());
                }

                /**
                 * Attempt the data transfer on the stealerNode through the
                 * {@link AdminClient#rebalanceNode()} api.<br>
                 * Blocks untill the AsyncStatus is set to success or exception <br>
                 * 
                 * @param stealerNodeId
                 * @param stealPartitionsMap
                 * @return success: true or false
                 */
                private boolean attemptRebalanceTransfer(int stealerNodeId,
                                                         RebalanceStealInfo stealInfo) {

                    if(stealInfo.getAttempt() < config.getMaxRebalancingAttempt()) {
                        try {
                            int rebalanceAsyncId = getAdminClient().rebalanceNode(stealerNodeId,
                                                                                  stealInfo);

                            adminClient.waitForCompletion(stealerNodeId,
                                                          rebalanceAsyncId,
                                                          24 * 60 * 60,
                                                          TimeUnit.SECONDS);
                            return true;
                        } catch(Exception e) {
                            logger.warn("Failed Attempt number " + stealInfo.getAttempt()
                                        + " Rebalance transfer " + stealerNodeId + " <== "
                                        + stealInfo.getDonorId() + " "
                                        + stealInfo.getPartitionList() + " with exception:", e);
                        }
                    }
                    return false;
                }

            });
        }

    }

    private Map<Integer, AtomicBoolean> createRebalancingLocks(Map<Integer, List<RebalanceStealInfo>> stealPartitionsMap) {
        Map<Integer, AtomicBoolean> map = new HashMap<Integer, AtomicBoolean>();
        for(int stealerNode: stealPartitionsMap.keySet()) {
            map.put(stealerNode, new AtomicBoolean(false));
        }
        return map;
    }

    private boolean acquireSemaphore(Semaphore semaphore) {
        try {
            logger.debug("Request to acquire rebalancing semaphore.");
            semaphore.acquire();
            logger.debug("rebalancing semaphore acquired.");
            return true;
        } catch(InterruptedException e) {
            // ignore
        }

        return false;
    }

    public AdminClient getAdminClient() {
        return adminClient;
    }

    public void stop() {
        adminClient.stop();
    }
}
