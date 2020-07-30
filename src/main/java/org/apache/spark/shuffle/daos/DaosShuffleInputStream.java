/*
 * (C) Copyright 2018-2020 Intel Corporation.
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
 *
 * GOVERNMENT LICENSE RIGHTS-OPEN SOURCE SOFTWARE
 * The Government's rights to use, modify, reproduce, release, perform, display,
 * or disclose this software are subject to the terms of the Apache License as
 * provided in Contract No. B609815.
 * Any reproduction of computer software, computer software documentation, or
 * portions thereof marked with this legend must also reproduce the markings.
 */

package org.apache.spark.shuffle.daos;

import io.daos.obj.DaosObject;
import io.daos.obj.IODataDesc;
import io.netty.buffershade5.ByteBuf;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkEnv;
import org.apache.spark.shuffle.ShuffleReadMetricsReporter;
import org.apache.spark.storage.BlockId;
import org.apache.spark.storage.BlockManagerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.Tuple3;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@NotThreadSafe
public class DaosShuffleInputStream extends InputStream {

  private DaosReader reader;

  private DaosObject object;

  private Executor executor;

  private ReaderConfig config;

  private ShuffleReadMetricsReporter metrics;

  private boolean fromOtherThread = true;

  private boolean cleaned;

  // ensure the order of partition
  private LinkedHashMap<Tuple2<Integer, Integer>, Tuple3<Long, BlockId, BlockManagerId>> partSizeMap;
  private Iterator<Tuple2<Integer, Integer>> mapIdIt;

  private BufferSource source;

  private static final Logger log = LoggerFactory.getLogger(DaosShuffleInputStream.class);

  public DaosShuffleInputStream(
      DaosReader reader,
      LinkedHashMap<Tuple2<Integer, Integer>, Tuple3<Long, BlockId, BlockManagerId>> partSizeMap,
      long maxBytesInFlight, long maxMem, ShuffleReadMetricsReporter metrics) {
    this.partSizeMap = partSizeMap;
    this.reader = reader;
    this.source = new BufferSource();
    reader.register(source);
    this.executor = reader.nextReaderExecutor();
    this.object = reader.getObject();
    this.config = new ReaderConfig(maxBytesInFlight, maxMem);
    this.metrics = metrics;
    this.mapIdIt = partSizeMap.keySet().iterator();
  }

  public BlockId getCurBlockId() {
    if (source.lastMapReduceIdForSubmit == null) {
      return null;
    }
    return partSizeMap.get(source.lastMapReduceIdForSubmit)._2();
  }

  public BlockManagerId getCurOriginAddress() {
    if (source.lastMapReduceIdForSubmit == null) {
      return null;
    }
    return partSizeMap.get(source.lastMapReduceIdForSubmit)._3();
  }

  public int getCurMapIndex() {
    return source.lastMapReduceIdForSubmit._1;
  }

  @Override
  public int read() throws IOException {
    while (true) {
      ByteBuf buf = source.nextBuf();
      if (buf == null) { // reach end
        return completeAndCleanup();
      }
      if (buf.readableBytes() >= 1) {
        metrics.incRemoteBytesRead(1);
        return buf.readByte();
      }
    }
  }

  @Override
  public int read(byte[] bytes) throws IOException {
    int len = read(bytes, 0, bytes.length);
    metrics.incRemoteBytesRead(len);
    return len;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    int len = length;
    while (true) {
      ByteBuf buf = source.nextBuf();
      if (buf == null) { // reach end
        return completeAndCleanup();
      }
      if (len <= buf.readableBytes()) {
        buf.readBytes(bytes, offset, len);
        metrics.incRemoteBytesRead(length);
        return length;
      }
      int maxRead = buf.readableBytes();
      buf.readBytes(bytes, offset, maxRead);
      offset += maxRead;
      len -= maxRead;
    }
  }

  private int completeAndCleanup() throws IOException {
    source.checkPartitionSize();
    source.checkTotalPartitions();
    return -1;
  }

  private void cleanup() {
    if (!cleaned) {
      boolean allReleased = source.cleanup(false);
      if (allReleased) {
        reader.unregister(source);
      }
      source = null;
      cleaned = true;
    }
  }

  @Override
  public void close() {
    cleanup();
  }

  public class BufferSource {
    private DaosReader.ReadTaskContext headCtx;
    private DaosReader.ReadTaskContext currentCtx;
    private DaosReader.ReadTaskContext lastCtx;
    private Deque<DaosReader.ReadTaskContext> consumedStack = new LinkedList<>();
    private IODataDesc currentDesc;
    private IODataDesc.Entry currentEntry;
    private long currentPartSize;

    private int entryIdx;
    private Tuple2<Integer, Integer> curMapReduceId;
    private Tuple2<Integer, Integer> lastMapReduceIdForSubmit;
    private Tuple2<Integer, Integer> lastMapReduceIdForReturn;
    private int curOffset;

    private Lock takeLock = new ReentrantLock();
    private Condition notEmpty = takeLock.newCondition();
    private AtomicInteger counter = new AtomicInteger(0);

    private int totalParts = partSizeMap.size();
    private int partsRead;

    private int exceedWaitTimes;
    private long totalInMemSize;
    private int totalSubmitted;

    /**
     * invoke this method when fromOtherThread is false.
     *
     * @return
     * @throws IOException
     */
    public ByteBuf readBySelf() throws IOException {
      if (lastCtx != null) { // duplicated IODataDescs which were submitted to other thread, but cancelled
        ByteBuf buf = readDuplicated();
        if (buf != null) {
          return buf;
        }
      }
      IODataDesc desc = createNextDesc(config.maxBytesInFlight);
      return getBySelf(desc, lastMapReduceIdForSubmit);
    }

    public ByteBuf nextBuf() throws IOException {
      ByteBuf buf = tryCurrentEntry();
      if (buf != null) {
        return buf;
      }
      // next entry
      buf = tryCurrentDesc();
      if (buf != null) {
        return buf;
      }
      // from next partition
      if (fromOtherThread) {
        // next ready queue
        if (headCtx != null) {
          return tryNextTaskContext();
        }
        // get data by self and submit request for remaining data
        return getBySelfAndSubmitMore(config.minReadSize);
      }
      // get data by self after fromOtherThread disabled
      return readBySelf();
    }

    private ByteBuf tryNextTaskContext() throws IOException {
      if (partsRead == totalParts) { // all partitions are read
        return null;
      }
      if (partsRead > totalParts) {
        throw new IllegalStateException("partsRead (" + partsRead + ") should not be bigger than totalParts (" +
            totalParts + ")");
      }
      try {
        IODataDesc desc;
        if ((desc = tryGetFromOtherThread()) != null) {
          submitMore();
          return validateLastEntryAndGetBuf(desc.getEntry(entryIdx));
        }
        // duplicate and get data by self
        return readDuplicated();
      } catch (InterruptedException e) {
        throw new IOException("read interrupted.", e);
      }
    }

    /**
     * we have to duplicate since mapId was moved.
     *
     * @return
     * @throws IOException
     */
    private ByteBuf readDuplicated() throws IOException {
      // in case no even single return from other thread
      if (currentCtx == null) {
        currentCtx = headCtx;
      } else {
        lastMapReduceIdForReturn = currentCtx.getMapReduceId();
        // no consumedStack push and no totalInMemSize and totalSubmitted update
        // since they will be updated when the task context finally returned
        currentCtx = currentCtx.getNext();
        if (currentCtx == null) {
          if (!fromOtherThread) {
            lastCtx = null;
          }
          return null;
        }
      }
      IODataDesc newDesc = currentCtx.getDesc().duplicate();
      return getBySelf(newDesc, currentCtx.getMapReduceId());
    }

    /* put last task context to consumedStack */
    private IODataDesc tryGetFromOtherThread() throws InterruptedException, IOException {
      IODataDesc desc = tryGetValidReady();
      if (desc != null) {
        return desc;
      }
      // wait for specified time
      desc = waitForValidFromOtherThread();
      if (desc != null) {
        return desc;
      }
      // check wait times and cancel task
      boolean cancelAll = false;
      if (exceedWaitTimes >= config.waitTimeoutTimes) {
        fromOtherThread = false;
        log.info("stop reading from dedicated read thread");
        cancelAll = true;
      } else {
        // try again
        desc = tryGetValidReady();
        if (desc != null) {
          return desc;
        }
      }
      // cancel tasks
      cancelTasks(cancelAll);
      return null;
    }

    private IODataDesc waitForValidFromOtherThread() throws InterruptedException, IOException {
      IODataDesc desc;
      while (true) {
        boolean timeout = false;
        takeLock.lockInterruptibly();
        try {
          long start = System.nanoTime();
          if (!notEmpty.await(config.waitDataTimeMs, TimeUnit.MILLISECONDS)) {
            timeout = true;
          }
          metrics.incFetchWaitTime(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } finally {
          takeLock.unlock();
        }
        if (timeout) {
          log.warn("exceed wait: {}ms, times: {}", config.waitDataTimeMs, exceedWaitTimes);
          exceedWaitTimes++;
          return null;
        }
        // get some results after wait
        int c = counter.getAndDecrement();
        if (c < 0) {
          throw new IllegalStateException("counter should be non-negative, " + c);
        }
        desc = tryGetValidReady();
        if (desc != null) {
          return desc;
        }
      }
    }

    private void cancelTasks(boolean cancelAll) {
      DaosReader.ReadTaskContext ctx = currentCtx;
      if (ctx == null) {
        ctx = headCtx;
      }
      ctx.cancel();
      if (cancelAll) {
        ctx = ctx.getNext();
        while (ctx != null) {
          ctx.cancel();
          ctx = ctx.getNext();
        }
      }
    }

    private IODataDesc tryGetValidReady() throws IOException {
      while (counter.get() > 0) {
        counter.getAndDecrement();
        IODataDesc desc = moveToNextDesc();
        if (desc != null) {
          return desc;
        }
      }
      return null;
    }

    private IODataDesc moveToNextDesc() throws IOException {
      if (currentCtx != null) {
        lastMapReduceIdForReturn = currentCtx.getMapReduceId();
        consumedStack.push(currentCtx);
        totalInMemSize -= currentCtx.getDesc().getTotalRequestSize();
        totalSubmitted -= 1;
        currentCtx = currentCtx.getNext();
      } else { // returned first time
        currentCtx = headCtx;
      }
      IODataDesc desc = validateReturnedTaskContext(currentCtx);
      if (desc == null) {
        return null;
      }
      metrics.incRemoteBlocksFetched(1);
      currentDesc = desc;
      return currentDesc;
    }

    private ByteBuf tryCurrentDesc() throws IOException {
      if (currentDesc != null) {
        ByteBuf buf;
        while (entryIdx < currentDesc.numberOfEntries()) {
          IODataDesc.Entry entry = currentDesc.getEntry(entryIdx);
          buf = validateLastEntryAndGetBuf(entry);
          if (buf.readableBytes() > 0) {
            return buf;
          }
          entryIdx++;
        }
        entryIdx = 0;
        // no need to release desc since all its entries are released in tryCurrentEntry and
        // internal buffers are released after object.fetch
        // reader.close will release all in case of failure
        currentDesc = null;
      }
      return null;
    }

    private ByteBuf tryCurrentEntry() {
      if (currentEntry != null) {
        ByteBuf buf = currentEntry.getFetchedData();
        if (buf.readableBytes() > 0) {
          return buf;
        }
        // release buffer as soon as possible
        currentEntry.releaseFetchDataBuffer();
        entryIdx++;
      }
      // not null currentEntry since it will be used for size validation
      return null;
    }

    /**
     * for first read.
     *
     * @param selfReadLimit
     * @return
     * @throws IOException
     */
    private ByteBuf getBySelfAndSubmitMore(long selfReadLimit) throws IOException {
      entryIdx = 0;
      // fetch the next by self
      IODataDesc desc = createNextDesc(selfReadLimit);
      Tuple2<Integer, Integer> mapreduceId = lastMapReduceIdForSubmit;
      try {
        if (fromOtherThread) {
          submitMore();
        }
      } catch (Exception e) {
        desc.release();
        if (e instanceof IOException) {
          throw (IOException)e;
        }
        throw new IOException("failed to submit more", e);
      }
      // first time read from reduce task
      return getBySelf(desc, mapreduceId);
    }

    private void submitMore() throws IOException {
      while (totalSubmitted < config.readBatchSize && totalInMemSize < config.maxMem) {
        IODataDesc taskDesc = createNextDesc(config.maxBytesInFlight);
        if (taskDesc == null) {
          break;
        }
        DaosReader.ReadTaskContext context = tryReuseContext(taskDesc);
        executor.execute(DaosReader.ReadTask.newInstance(context));
        totalInMemSize += taskDesc.getTotalRequestSize();
        totalSubmitted++;
      }
    }

    private DaosReader.ReadTaskContext tryReuseContext(IODataDesc desc) {
      DaosReader.ReadTaskContext context = consumedStack.pop();
      if (context != null) {
        context.reuse(desc, lastMapReduceIdForSubmit);
      } else {
        context = new DaosReader.ReadTaskContext(object, counter, takeLock, notEmpty, desc, lastMapReduceIdForSubmit);
      }
      if (lastCtx != null) {
        lastCtx.setNext(context);
      }
      lastCtx = context;
      if (headCtx == null) {
        headCtx = context;
      }
      return context;
    }

    private ByteBuf getBySelf(IODataDesc desc, Tuple2<Integer, Integer> mapreduceId) throws IOException {
      // get data by self, no need to release currentDesc
      if (desc == null) { // reach end
        return null;
      }
      boolean releaseBuf = false;
      try {
        object.fetch(desc);
        metrics.incRemoteBlocksFetched(1);
        currentDesc = desc;
        ByteBuf buf = validateLastEntryAndGetBuf(desc.getEntry(entryIdx));
        lastMapReduceIdForReturn = mapreduceId;
        return buf;
      } catch (IOException | IllegalStateException e) {
        releaseBuf = true;
        throw e;
      } finally {
        desc.release(releaseBuf);
      }
    }

    private IODataDesc createNextDesc(long sizeLimit) throws IOException {
      List<IODataDesc.Entry> entries = new ArrayList<>();
      long remaining = sizeLimit;
      int reduceId = -1;
      int mapId;
      while (remaining > 0) {
        nextMapReduceId();
        if (curMapReduceId == null) {
          break;
        }
        if (reduceId > 0 && curMapReduceId._2 != reduceId) { // make sure entries under same reduce
          break;
        }
        reduceId = curMapReduceId._2;
        mapId = curMapReduceId._1;
        lastMapReduceIdForSubmit = curMapReduceId;
        long readSize = partSizeMap.get(curMapReduceId)._1() - curOffset;
        long offset = curOffset;
        if (readSize > remaining) {
          readSize = remaining;
          curOffset += readSize;
        } else {
          curOffset = 0;
          curMapReduceId = null;
        }
        entries.add(createEntry(mapId, offset, readSize));
        remaining -= readSize;
      }
      if (entries.isEmpty()) {
        return null;
      }
      return object.createDataDescForFetch(String.valueOf(reduceId), entries);
    }

    private void nextMapReduceId() {
      if (curMapReduceId != null) {
        return;
      }
      curOffset = 0;
      if (mapIdIt.hasNext()) {
        curMapReduceId = mapIdIt.next();
        partsRead++;
      } else {
        curMapReduceId = null;
      }
    }

    private IODataDesc.Entry createEntry(int mapId, long offset, long readSize) throws IOException {
      return IODataDesc.createEntryForFetch(String.valueOf(mapId), IODataDesc.IodType.ARRAY, 1, (int)offset,
          (int)readSize);
    }

    private IODataDesc validateReturnedTaskContext(DaosReader.ReadTaskContext context)
        throws IOException {
      if (context.isCancelled()) {
        // no more release here since it's released in ReadTask already when it's cancelled.
        consumedStack.push(context);
        totalInMemSize -= currentCtx.getDesc().getTotalRequestSize();
        totalSubmitted -= 1;
        return null;
      }
      IODataDesc desc = context.getDesc();
      if (!desc.succeeded()) {
        String msg = "failed to get data from DAOS, desc: " + desc.toString(4096);
        if (desc.getCause() != null) {
          throw new IOException(msg, desc.getCause());
        } else {
          throw new IllegalStateException(msg + "\nno exception got. logic error or crash?");
        }
      }
      return desc;
    }

    private ByteBuf validateLastEntryAndGetBuf(IODataDesc.Entry entry) throws IOException {
      ByteBuf buf = entry.getFetchedData();
      if (currentEntry != null && entry != currentEntry) {
        if (entry.getKey().equals(currentEntry.getKey())) {
          currentPartSize += buf.readableBytes();
        } else {
          checkPartitionSize();
          currentPartSize = buf.readableBytes();
        }
      }
      currentEntry = entry;
      return buf;
    }

    private void checkPartitionSize() throws IOException {
      if (lastMapReduceIdForReturn == null) {
        return;
      }
      // partition size is not accurate after compress/decompress
      long size = partSizeMap.get(lastMapReduceIdForReturn)._1();
      if (size < 35 * 1024 * 1024 * 1024 && currentPartSize * 1.1 < size) {
        throw new IOException("expect partition size " + partSizeMap.get(lastMapReduceIdForReturn) +
            ", actual size " + currentPartSize + ", mapId and reduceId: " + lastMapReduceIdForReturn);
      }
    }

    public boolean cleanup(boolean force) {
      boolean allReleased = true;
      if (!cleaned) {
        allReleased &= cleanupTaskContext(currentCtx, force);
        allReleased &= cleanupTaskContexts(consumedStack, force);
      }
      return allReleased;
    }

    private boolean cleanupTaskContexts(Collection<DaosReader.ReadTaskContext> collection, boolean force) {
      boolean allReleased = true;
      for (DaosReader.ReadTaskContext ctx : collection) {
        allReleased &= cleanupTaskContext(ctx, force);
      }
      if (allReleased) {
        collection.clear();
      }
      return allReleased;
    }

    private boolean cleanupTaskContext(DaosReader.ReadTaskContext ctx, boolean force) {
      if (ctx == null) {
        return true;
      }
      IODataDesc desc = ctx.getDesc();
      if (desc != null && (force || desc.succeeded() || ctx.isCancelled())) {
        desc.release();
        return true;
      }
      return false;
    }

    public void checkTotalPartitions() throws IOException {
      if (partsRead != totalParts) {
        throw new IOException("expect total partitions to be read: " + totalParts + ", actual read: " + partsRead);
      }
    }
  }

  private static final class ReaderConfig {
    private long minReadSize;
    private long maxBytesInFlight;
    private long maxMem;
    private int readBatchSize;
    private int waitDataTimeMs;
    private int waitTimeoutTimes;

    private ReaderConfig(long maxBytesInFlight, long maxMem) {
      SparkConf conf = SparkEnv.get().conf();
      minReadSize = (long)conf.get(package$.MODULE$.SHUFFLE_DAOS_READ_MINIMUM_SIZE()) * 1024 * 1024;
      if (maxBytesInFlight < minReadSize) {
        this.maxBytesInFlight = minReadSize;
      } else {
        this.maxBytesInFlight = maxBytesInFlight;
      }
      this.maxMem = maxMem;
      this.readBatchSize = (int)conf.get(package$.MODULE$.SHUFFLE_DAOS_READ_BATCH_SIZE());
      this.waitDataTimeMs = (int)conf.get(package$.MODULE$.SHUFFLE_DAOS_READ_WAIT_DATA_MS());
      this.waitTimeoutTimes = (int)conf.get(package$.MODULE$.SHUFFLE_DAOS_READ_WAIT_DATA_TIMEOUT_TIMES());
    }

  }
}