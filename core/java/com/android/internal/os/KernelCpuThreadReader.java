/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import android.annotation.Nullable;
import android.os.Process;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * Given a process, will iterate over the child threads of the process, and return the CPU usage
 * statistics for each child thread. The CPU usage statistics contain the amount of time spent in a
 * frequency band.
 *
 * <p>Frequencies are bucketed together to reduce the amount of data created. This means that we
 * return {@link #NUM_BUCKETS} frequencies instead of the full number. Frequencies are reported as
 * the lowest frequency in that range. Frequencies are spread as evenly as possible across the
 * buckets. The buckets do not cross over the little/big frequencies reported.
 *
 * <p>N.B.: In order to bucket across little/big frequencies correctly, we assume that the {@code
 * time_in_state} file contains every little core frequency in ascending order, followed by every
 * big core frequency in ascending order. This assumption might not hold for devices with different
 * kernel implementations of the {@code time_in_state} file generation.
 */
public class KernelCpuThreadReader {

    private static final String TAG = "KernelCpuThreadReader";

    private static final boolean DEBUG = false;

    /**
     * The name of the file to read CPU statistics from, must be found in {@code
     * /proc/$PID/task/$TID}
     */
    private static final String CPU_STATISTICS_FILENAME = "time_in_state";

    /**
     * The name of the file to read process command line invocation from, must be found in
     * {@code /proc/$PID/}
     */
    private static final String PROCESS_NAME_FILENAME = "cmdline";

    /**
     * The name of the file to read thread name from, must be found in
     * {@code /proc/$PID/task/$TID}
     */
    private static final String THREAD_NAME_FILENAME = "comm";

    /**
     * Default process name when the name can't be read
     */
    private static final String DEFAULT_PROCESS_NAME = "unknown_process";

    /**
     * Default thread name when the name can't be read
     */
    private static final String DEFAULT_THREAD_NAME = "unknown_thread";

    /**
     * Default mount location of the {@code proc} filesystem
     */
    private static final Path DEFAULT_PROC_PATH = Paths.get("/proc");

    /**
     * The initial {@code time_in_state} file for {@link ProcTimeInStateReader}
     */
    private static final Path DEFAULT_INITIAL_TIME_IN_STATE_PATH =
            DEFAULT_PROC_PATH.resolve("self/time_in_state");

    /**
     * Number of frequency buckets
     */
    private static final int NUM_BUCKETS = 8;

    /**
     * Where the proc filesystem is mounted
     */
    private final Path mProcPath;

    /**
     * Frequencies read from the {@code time_in_state} file. Read from {@link
     * #mProcTimeInStateReader#getCpuFrequenciesKhz()} and cast to {@code int[]}
     */
    private final int[] mFrequenciesKhz;

    /**
     * Used to read and parse {@code time_in_state} files
     */
    private final ProcTimeInStateReader mProcTimeInStateReader;

    /**
     * Used to sort frequencies and usage times into buckets
     */
    private final FrequencyBucketCreator mFrequencyBucketCreator;

    private KernelCpuThreadReader() throws IOException {
        this(DEFAULT_PROC_PATH, DEFAULT_INITIAL_TIME_IN_STATE_PATH);
    }

    /**
     * Create with a path where `proc` is mounted. Used primarily for testing
     *
     * @param procPath where `proc` is mounted (to find, see {@code mount | grep ^proc})
     * @param initialTimeInStatePath where the initial {@code time_in_state} file exists to define
     * format
     */
    @VisibleForTesting
    public KernelCpuThreadReader(Path procPath, Path initialTimeInStatePath) throws IOException {
        mProcPath = procPath;
        mProcTimeInStateReader = new ProcTimeInStateReader(initialTimeInStatePath);

        // Copy mProcTimeInState's frequencies and initialize bucketing
        final long[] frequenciesKhz = mProcTimeInStateReader.getFrequenciesKhz();
        mFrequencyBucketCreator = new FrequencyBucketCreator(frequenciesKhz, NUM_BUCKETS);
        mFrequenciesKhz = mFrequencyBucketCreator.getBucketMinFrequencies(frequenciesKhz);
    }

    /**
     * Create the reader and handle exceptions during creation
     *
     * @return the reader, null if an exception was thrown during creation
     */
    @Nullable
    public static KernelCpuThreadReader create() {
        try {
            return new KernelCpuThreadReader();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to initialize KernelCpuThreadReader", e);
            return null;
        }
    }

    /**
     * Read all of the CPU usage statistics for each child thread of the current process
     *
     * @return process CPU usage containing usage of all child threads
     */
    @Nullable
    public ProcessCpuUsage getCurrentProcessCpuUsage() {
        return getProcessCpuUsage(
                mProcPath.resolve("self"),
                Process.myPid(),
                Process.myUid());
    }

    /**
     * Read all of the CPU usage statistics for each child thread of a process
     *
     * @param processPath the {@code /proc} path of the thread
     * @param processId the ID of the process
     * @param uid the ID of the user who owns the process
     * @return process CPU usage containing usage of all child threads
     */
    @Nullable
    private ProcessCpuUsage getProcessCpuUsage(Path processPath, int processId, int uid) {
        if (DEBUG) {
            Slog.d(TAG, "Reading CPU thread usages with directory " + processPath
                    + " process ID " + processId
                    + " and user ID " + uid);
        }

        final Path allThreadsPath = processPath.resolve("task");
        final ArrayList<ThreadCpuUsage> threadCpuUsages = new ArrayList<>();
        try (DirectoryStream<Path> threadPaths = Files.newDirectoryStream(allThreadsPath)) {
            for (Path threadDirectory : threadPaths) {
                ThreadCpuUsage threadCpuUsage = getThreadCpuUsage(threadDirectory);
                if (threadCpuUsage != null) {
                    threadCpuUsages.add(threadCpuUsage);
                }
            }
        } catch (IOException e) {
            // Expected when a process finishes
            return null;
        }

        // If we found no threads, then the process has exited while we were reading from it
        if (threadCpuUsages.isEmpty()) {
            return null;
        }

        if (DEBUG) {
            Slog.d(TAG, "Read CPU usage of " + threadCpuUsages.size() + " threads");
        }
        return new ProcessCpuUsage(
                processId,
                getProcessName(processPath),
                uid,
                threadCpuUsages);
    }

    /**
     * Get the CPU frequencies that correspond to the times reported in
     * {@link ThreadCpuUsage#usageTimesMillis}
     */
    @Nullable
    public int[] getCpuFrequenciesKhz() {
        return mFrequenciesKhz;
    }

    /**
     * Get a thread's CPU usage
     *
     * @param threadDirectory the {@code /proc} directory of the thread
     * @return null in the case that the directory read failed
     */
    @Nullable
    private ThreadCpuUsage getThreadCpuUsage(Path threadDirectory) {
        // Get the thread ID from the directory name
        final int threadId;
        try {
            final String directoryName = threadDirectory.getFileName().toString();
            threadId = Integer.parseInt(directoryName);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed to parse thread ID when iterating over /proc/*/task", e);
            return null;
        }

        // Get the thread name from the thread directory
        final String threadName = getThreadName(threadDirectory);

        // Get the CPU statistics from the directory
        final Path threadCpuStatPath = threadDirectory.resolve(CPU_STATISTICS_FILENAME);
        final long[] cpuUsagesLong = mProcTimeInStateReader.getUsageTimesMillis(threadCpuStatPath);
        if (cpuUsagesLong == null) {
            return null;
        }
        int[] cpuUsages = mFrequencyBucketCreator.getBucketedValues(cpuUsagesLong);

        return new ThreadCpuUsage(threadId, threadName, cpuUsages);
    }

    /**
     * Get the command used to start a process
     */
    private String getProcessName(Path processPath) {
        final Path processNamePath = processPath.resolve(PROCESS_NAME_FILENAME);

        final String processName =
                ProcStatsUtil.readSingleLineProcFile(processNamePath.toString());
        if (processName != null) {
            return processName;
        }
        return DEFAULT_PROCESS_NAME;
    }

    /**
     * Get the name of a thread, given the {@code /proc} path of the thread
     */
    private String getThreadName(Path threadPath) {
        final Path threadNamePath = threadPath.resolve(THREAD_NAME_FILENAME);
        final String threadName =
                ProcStatsUtil.readNullSeparatedFile(threadNamePath.toString());
        if (threadName == null) {
            return DEFAULT_THREAD_NAME;
        }
        return threadName;
    }

    /**
     * Puts frequencies and usage times into buckets
     */
    @VisibleForTesting
    public static class FrequencyBucketCreator {
        private final int mNumBuckets;
        private final int mNumFrequencies;
        private final int mBigFrequenciesStartIndex;
        private final int mLittleNumBuckets;
        private final int mBigNumBuckets;
        private final int mLittleBucketSize;
        private final int mBigBucketSize;

        /**
         * Buckets based of a list of frequencies
         *
         * @param frequencies the frequencies to base buckets off
         * @param numBuckets how many buckets to create
         */
        @VisibleForTesting
        public FrequencyBucketCreator(long[] frequencies, int numBuckets) {
            Preconditions.checkArgument(numBuckets > 0);

            mNumFrequencies = frequencies.length;
            mBigFrequenciesStartIndex = getBigFrequenciesStartIndex(frequencies);

            final int littleNumBuckets;
            final int bigNumBuckets;
            if (mBigFrequenciesStartIndex < frequencies.length) {
                littleNumBuckets = numBuckets / 2;
                bigNumBuckets = numBuckets - littleNumBuckets;
            } else {
                // If we've got no big frequencies, set all buckets to little frequencies
                littleNumBuckets = numBuckets;
                bigNumBuckets = 0;
            }

            // Ensure that we don't have more buckets than frequencies
            mLittleNumBuckets = Math.min(littleNumBuckets, mBigFrequenciesStartIndex);
            mBigNumBuckets = Math.min(
                    bigNumBuckets, frequencies.length - mBigFrequenciesStartIndex);
            mNumBuckets = mLittleNumBuckets + mBigNumBuckets;

            // Set the size of each little and big bucket. If they have no buckets, the size is zero
            mLittleBucketSize = mLittleNumBuckets == 0 ? 0 :
                    mBigFrequenciesStartIndex / mLittleNumBuckets;
            mBigBucketSize = mBigNumBuckets == 0 ? 0 :
                    (frequencies.length - mBigFrequenciesStartIndex) / mBigNumBuckets;
        }

        /**
         * Find the index where frequencies change from little core to big core
         */
        @VisibleForTesting
        public static int getBigFrequenciesStartIndex(long[] frequenciesKhz) {
            for (int i = 0; i < frequenciesKhz.length - 1; i++) {
                if (frequenciesKhz[i] > frequenciesKhz[i + 1]) {
                    return i + 1;
                }
            }

            return frequenciesKhz.length;
        }

        /**
         * Get the minimum frequency in each bucket
         */
        @VisibleForTesting
        public int[] getBucketMinFrequencies(long[] frequenciesKhz) {
            Preconditions.checkArgument(frequenciesKhz.length == mNumFrequencies);
            // If there's only one bucket, we bucket everything together so the first bucket is the
            // min frequency
            if (mNumBuckets == 1) {
                return new int[]{(int) frequenciesKhz[0]};
            }

            final int[] bucketMinFrequencies = new int[mNumBuckets];
            // Initialize little buckets min frequencies
            for (int i = 0; i < mLittleNumBuckets; i++) {
                bucketMinFrequencies[i] = (int) frequenciesKhz[i * mLittleBucketSize];
            }
            // Initialize big buckets min frequencies
            for (int i = 0; i < mBigNumBuckets; i++) {
                final int frequencyIndex = mBigFrequenciesStartIndex + i * mBigBucketSize;
                bucketMinFrequencies[mLittleNumBuckets + i] = (int) frequenciesKhz[frequencyIndex];
            }
            return bucketMinFrequencies;
        }

        /**
         * Put an array of values into buckets. This takes a {@code long[]} and returns {@code
         * int[]} as everywhere this method is used will have to do the conversion anyway, so we
         * save time by doing it here instead
         *
         * @param values the values to bucket
         * @return the bucketed usage times
         */
        @VisibleForTesting
        public int[] getBucketedValues(long[] values) {
            Preconditions.checkArgument(values.length == mNumFrequencies);
            final int[] bucketed = new int[mNumBuckets];

            // If there's only one bucket, add all frequencies in
            if (mNumBuckets == 1) {
                for (int i = 0; i < values.length; i++) {
                    bucketed[0] += values[i];
                }
                return bucketed;
            }

            // Initialize the little buckets
            for (int i = 0; i < mBigFrequenciesStartIndex; i++) {
                final int bucketIndex = Math.min(i / mLittleBucketSize, mLittleNumBuckets - 1);
                bucketed[bucketIndex] += values[i];
            }
            // Initialize the big buckets
            for (int i = mBigFrequenciesStartIndex; i < values.length; i++) {
                final int bucketIndex = Math.min(
                        mLittleNumBuckets + (i - mBigFrequenciesStartIndex) / mBigBucketSize,
                        mNumBuckets - 1);
                bucketed[bucketIndex] += values[i];
            }
            return bucketed;
        }
    }

    /**
     * CPU usage of a process
     */
    public static class ProcessCpuUsage {
        public final int processId;
        public final String processName;
        public final int uid;
        public final ArrayList<ThreadCpuUsage> threadCpuUsages;

        ProcessCpuUsage(
                int processId,
                String processName,
                int uid,
                ArrayList<ThreadCpuUsage> threadCpuUsages) {
            this.processId = processId;
            this.processName = processName;
            this.uid = uid;
            this.threadCpuUsages = threadCpuUsages;
        }
    }

    /**
     * CPU usage of a thread
     */
    public static class ThreadCpuUsage {
        public final int threadId;
        public final String threadName;
        public final int[] usageTimesMillis;

        ThreadCpuUsage(
                int threadId,
                String threadName,
                int[] usageTimesMillis) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.usageTimesMillis = usageTimesMillis;
        }
    }
}
