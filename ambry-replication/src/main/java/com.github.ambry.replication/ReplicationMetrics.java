package com.github.ambry.replication;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import java.util.ArrayList;
import java.util.List;


/**
 * Metrics for Replication
 */
public class ReplicationMetrics {

  public final Counter interColoReplicationBytesCount;
  public final Counter intraColoReplicationBytesCount;
  public final Counter interColoBlobsReplicatedCount;
  public final Counter intraColoBlobsReplicatedCount;
  public final Counter unknownRemoteReplicaRequestCount;
  public final Counter replicationErrors;
  public final Timer interColoReplicationLatency;
  public final Timer intraColoReplicationLatency;
  public final Histogram remoteReplicaTokensPersistTime;
  public final Histogram remoteReplicaTokensRestoreTime;
  public Gauge<Integer> numberOfReplicaThreads;
  private List<ReplicaThread> replicaThreads;
  public List<Gauge<Long>> replicaLagInBytes;
  private MetricRegistry registry;

  public ReplicationMetrics(String name, MetricRegistry registry, List<ReplicaThread> replicaThreads) {
    interColoReplicationBytesCount =
        registry.counter(MetricRegistry.name(ReplicaThread.class, name + "-interColoReplicationBytesCount"));
    intraColoReplicationBytesCount =
        registry.counter(MetricRegistry.name(ReplicaThread.class, name + "-intraColoReplicationBytesCount"));
    interColoBlobsReplicatedCount =
        registry.counter(MetricRegistry.name(ReplicaThread.class, name + "-interColoReplicationBlobsCount"));
    intraColoBlobsReplicatedCount =
        registry.counter(MetricRegistry.name(ReplicaThread.class, name + "-intraColoBlobsReplicatedCount"));
    unknownRemoteReplicaRequestCount =
        registry.counter(MetricRegistry.name(ReplicaThread.class, name + "-unknownRemoteReplicaRequestCount"));
    registry.counter(MetricRegistry.name(ReplicaThread.class, name + "-intraColoReplicationBlobsCount"));
    replicationErrors = registry.counter(MetricRegistry.name(ReplicaThread.class, name + "-replicationErrors"));
    interColoReplicationLatency =
        registry.timer(MetricRegistry.name(ReplicaThread.class, name + "-interColoReplicationLatency"));
    intraColoReplicationLatency =
        registry.timer(MetricRegistry.name(ReplicaThread.class, name + "-intraColoReplicationLatency"));
    remoteReplicaTokensPersistTime =
        registry.histogram(MetricRegistry.name(ReplicaThread.class, "RemoteReplicaTokensPersistTime"));
    remoteReplicaTokensRestoreTime =
        registry.histogram(MetricRegistry.name(ReplicaThread.class, "RemoteReplicaTokensRestoreTime"));
    this.replicaThreads = replicaThreads;
    this.registry = registry;
    numberOfReplicaThreads = new Gauge<Integer>() {
      @Override
      public Integer getValue() {
        return getLiveThreads();
      }
    };

    registry.register(MetricRegistry.name(ReplicaThread.class, "NumberOfReplicaThreads"), numberOfReplicaThreads);
    this.replicaLagInBytes = new ArrayList<Gauge<Long>>();
  }

  private int getLiveThreads() {
    int count = 0;
    for (ReplicaThread thread : replicaThreads) {
      if (thread.isThreadUp()) {
        count++;
      }
    }
    return count;
  }

  public void addRemoteReplicaToLagMetrics(final RemoteReplicaInfo remoteReplicaInfo) {
    Gauge<Long> replicaLag = new Gauge<Long>() {
      @Override
      public Long getValue() {
        return remoteReplicaInfo.getReplicaLagInBytes();
      }
    };
    registry.register(
        MetricRegistry.name(ReplicationMetrics.class, remoteReplicaInfo.getReplicaId() + "-replicaLagInBytes"),
        replicaLag);
    replicaLagInBytes.add(replicaLag);
  }
}
