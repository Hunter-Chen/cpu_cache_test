package com.hunter.study.cache;

import lombok.extern.slf4j.Slf4j;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.RunnerException;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class CacheDemo {
  public static void main(String[] args) throws IOException, RunnerException {
    org.openjdk.jmh.Main.main(args);
  }

  private static final int CACHE_LINE_SIZE = 64;

  @Benchmark
  @Fork(
      value = 1,
      jvmArgs = {"-Xms1G", "-Xmx1G"})
  @Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
  @Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public long runDemo(PrepareWork prepareWork) throws Exception {
    long holder = 0L;
    final int repeat = 16384 / prepareWork.numOfPages;
    for (int i = 0; i < repeat; i++) {
      for (int j = 0; j < prepareWork.numOfCacheLines; j++) {
        // 随机数读写,而不是顺序读取,阻止cpu cache的pre-fetch.
        // 这里如果不用随机数,而是直接挨着累加,CPU会预先把数据从RAM里读进来放到CPU cache里.
        // 结果就是不同内存大小里面的数据的获取时间是一样的
        long myAddress =
            PrepareWork.startingAddress + CACHE_LINE_SIZE * prepareWork.randomAddressOffset[j];
        UnsafeUtil.getUnsafe().putLong(myAddress, myAddress);
      }
      for (int j = 0; j < prepareWork.numOfCacheLines; j++) {
        long myAddress =
            PrepareWork.startingAddress + CACHE_LINE_SIZE * prepareWork.randomAddressOffset[j];
        long aLong = UnsafeUtil.getUnsafe().getLong(myAddress);
        holder += aLong;
      }
    }
    return holder;
  }

  @State(Scope.Benchmark)
  public static class PrepareWork {
    // 一个内存页是4K
    private static final int RAM_PAGE_SIZE = 4096;

    // 在一个cpu缓存的cache line里面(64字节),把long放到哪.
    // 0就是0-8这个位置放一个long. 30就是30-38的位置放(放到缓存行的中间)
    // 如果60这里开始,意味着long跨了两个缓存行(第一行的60-64,第二行的0-4)
    // 理论上来说后者应该更慢,因为取一个long出来会跨两个cache line.
    // 理论上来说,放到缓存行中间也有可能取出来更慢. 因为CPU一次从内存里读一个字64bit,也就是8字节.
    // 也就是说第4次才能读到我们需要的数据. 但是实际情况中CPU会有很多优化
    @Param({"0","30","60"})
    int offset;

    // 装载多少内存页(也就是说分配多大的连续内存,当超过CPU缓存大小后,
    // 分配的越大,从里面随机查找数据的时间就应该更长. 因为CPU缓存大小是已经确定了的)
    @Param({
      "1", "2", "4", "8", "16", "32", "64","96","128", "256", "512", "1024", "2048", "4096", "16384"
    })
    int numOfPages;

    int numOfCacheLines;

    int[] randomAddressOffset;

    ByteBuffer buffer;

    public static long startingAddress;
    public static long endAddress;

    // 每次调用测试方法,都先分配内存. 上一次调用的内存会被JVM回收掉
    @Setup(Level.Invocation)
    public void setUp() throws Exception {
      // 回收上一次分配的内存
      buffer=null;
      System.gc();
      // 分配一块新的堆外的内存空间
      buffer = DataAllocation.allocateAlignedByteBuffer(numOfPages * RAM_PAGE_SIZE, RAM_PAGE_SIZE);
      numOfCacheLines = numOfPages * RAM_PAGE_SIZE / CACHE_LINE_SIZE;
      randomAddressOffset = new int[numOfCacheLines];
      Random random = new Random();
      for (int i = 0; i < numOfCacheLines; i++) {
        randomAddressOffset[i] = random.nextInt(numOfCacheLines);
      }
      startingAddress = DataAllocation.getAddress(buffer) + offset;
      endAddress = DataAllocation.getAddress(buffer) + buffer.remaining();
    }
  }
}

@Slf4j
class DataAllocation {
  private static final long addressOffset;

  static {
    try {
      addressOffset =
          UnsafeUtil.getUnsafe().objectFieldOffset(Buffer.class.getDeclaredField("address"));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 获得这块堆外内存空间的起始地址
   *
   * @param buffer
   * @return
   * @throws Exception
   */
  public static long getAddress(ByteBuffer buffer) throws Exception {
    return UnsafeUtil.getUnsafe().getLong(buffer, addressOffset);
  }

  /**
   * 在堆外里面直接分配一块内存, 并且内存开始的位置是页对齐的(4K对齐)
   *
   * @param capacity 分配的内存空间的总字节数
   * @param align 按照多少字节对齐
   * @return
   * @throws Exception
   */
  public static ByteBuffer allocateAlignedByteBuffer(int capacity, long align) throws Exception {

    if (Long.bitCount(align) != 1) {
      throw new IllegalArgumentException("Alignment must be a power of 2");
    }
    // 这里多加分配一点内存,后面因为如果没有对齐的话,需要调整一下
    ByteBuffer buffer = ByteBuffer.allocateDirect((int) (capacity + align));
    long address = getAddress(buffer);
    // 如果分配出来的内存刚好是对齐的
    if ((address & (align - 1)) == 0) {
      buffer.limit(capacity);
    } else {
      // 如果没有对齐,就要挪动一下这个buffer的初始写入位置,让它对齐
      int newPosition = (int) (align - (address & (align - 1)));
      buffer.position(newPosition);
      int newLimit = newPosition + capacity;
      buffer.limit(newLimit);
    }
    return buffer.slice().order(ByteOrder.nativeOrder());
  }
}

class UnsafeUtil {
  static Unsafe getUnsafe() throws Exception {
    Field f = Unsafe.class.getDeclaredField("theUnsafe");
    f.setAccessible(true);
    return (Unsafe) f.get(null);
  }
}
