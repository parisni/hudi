/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.action.clean;

import org.apache.hudi.avro.model.HoodieCleanMetadata;
import org.apache.hudi.avro.model.HoodieSavepointMetadata;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.model.CleanFileInfo;
import org.apache.hudi.common.model.CompactionOperation;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieCleaningPolicy;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieFileGroup;
import org.apache.hudi.common.model.HoodieFileGroupId;
import org.apache.hudi.common.model.HoodieLogFile;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.HoodieReplaceCommitMetadata;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.table.cdc.HoodieCDCUtils;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineMetadataUtils;
import org.apache.hudi.common.table.timeline.versioning.clean.CleanPlanV1MigrationHandler;
import org.apache.hudi.common.table.timeline.versioning.clean.CleanPlanV2MigrationHandler;
import org.apache.hudi.common.table.view.SyncableFileSystemView;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.HoodieSavepointException;
import org.apache.hudi.metadata.FileSystemBackedTableMetadata;
import org.apache.hudi.table.HoodieTable;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cleaner is responsible for garbage collecting older files in a given partition path. Such that
 * <p>
 * 1) It provides sufficient time for existing queries running on older versions, to close
 * <p>
 * 2) It bounds the growth of the files in the file system
 */
public class CleanPlanner<T extends HoodieRecordPayload, I, K, O> implements Serializable {

  private static final Logger LOG = LogManager.getLogger(CleanPlanner.class);

  public static final Integer CLEAN_PLAN_VERSION_1 = CleanPlanV1MigrationHandler.VERSION;
  public static final Integer CLEAN_PLAN_VERSION_2 = CleanPlanV2MigrationHandler.VERSION;
  public static final Integer LATEST_CLEAN_PLAN_VERSION = CLEAN_PLAN_VERSION_2;

  private final SyncableFileSystemView fileSystemView;
  private final HoodieTimeline commitTimeline;
  private final Map<HoodieFileGroupId, CompactionOperation> fgIdToPendingCompactionOperations;
  private HoodieTable<T, I, K, O> hoodieTable;
  private HoodieWriteConfig config;
  private transient HoodieEngineContext context;

  public CleanPlanner(HoodieEngineContext context, HoodieTable<T, I, K, O> hoodieTable, HoodieWriteConfig config) {
    this.context = context;
    this.hoodieTable = hoodieTable;
    this.fileSystemView = hoodieTable.getHoodieView();
    this.commitTimeline = hoodieTable.getCompletedCommitsTimeline();
    this.config = config;
    this.fgIdToPendingCompactionOperations =
        ((SyncableFileSystemView) hoodieTable.getSliceView()).getPendingCompactionOperations()
            .map(entry -> Pair.of(
                new HoodieFileGroupId(entry.getValue().getPartitionPath(), entry.getValue().getFileId()),
                entry.getValue()))
            .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  /**
   * Get the list of data file names savepointed.
   */
  public Stream<String> getSavepointedDataFiles(String savepointTime) {
    if (!hoodieTable.getSavepointTimestamps().contains(savepointTime)) {
      throw new HoodieSavepointException(
          "Could not get data files for savepoint " + savepointTime + ". No such savepoint.");
    }
    HoodieInstant instant = new HoodieInstant(false, HoodieTimeline.SAVEPOINT_ACTION, savepointTime);
    HoodieSavepointMetadata metadata;
    try {
      metadata = TimelineMetadataUtils.deserializeHoodieSavepointMetadata(
          hoodieTable.getActiveTimeline().getInstantDetails(instant).get());
    } catch (IOException e) {
      throw new HoodieSavepointException("Could not get savepointed data files for savepoint " + savepointTime, e);
    }
    return metadata.getPartitionMetadata().values().stream().flatMap(s -> s.getSavepointDataFile().stream());
  }

  /**
   * Returns list of partitions where clean operations needs to be performed.
   *
   * @param earliestRetainedInstant New instant to be retained after this cleanup operation
   * @return list of partitions to scan for cleaning
   * @throws IOException when underlying file-system throws this exception
   */
  public List<String> getPartitionPathsToClean(Option<HoodieInstant> earliestRetainedInstant) throws IOException {
    switch (config.getCleanerPolicy()) {
      case KEEP_LATEST_COMMITS:
      case KEEP_LATEST_BY_HOURS:
      case KEEP_LATEST_FILE_VERSIONS:
        return getPartitionPathsForClean(earliestRetainedInstant, config.getCleanerPolicy());
      default:
        throw new IllegalStateException("Unknown Cleaner Policy");
    }
  }

  /**
   * Return partition paths for cleaning.
   * If incremental cleaning mode is enabled:
   * Incase of cleaning based on LATEST_COMMITS and LATEST_HOURS, cleaner looks at the range of commit between commit retained during last clean
   * and earliest commit to retain for current clean.
   * Incase of cleaning based on LATEST_FILE_VERSIONS, cleaner looks at range of commits between lastCompletedCommit during last clean upto latest
   * completed in timeline.
   * If incremental cleaning is not enabled, all partitions paths are listed based on file listing.
   * @param instantToRetain Earliest Instant to retain.
   * @param cleaningPolicy cleaning policy used.
   * @return list of partitions to be used for cleaning.
   * @throws IOException
   */
  private List<String> getPartitionPathsForClean(Option<HoodieInstant> instantToRetain, HoodieCleaningPolicy cleaningPolicy) throws IOException {
    if (cleaningPolicy != HoodieCleaningPolicy.KEEP_LATEST_FILE_VERSIONS) {
      if (!instantToRetain.isPresent()) {
        LOG.info("No earliest commit to retain. No need to scan partitions !!");
        return Collections.emptyList();
      }

      if (config.incrementalCleanerModeEnabled()) {
        Option<HoodieInstant> lastClean = hoodieTable.getCleanTimeline().filterCompletedInstants().lastInstant();
        if (lastClean.isPresent()) {
          if (hoodieTable.getActiveTimeline().isEmpty(lastClean.get())) {
            hoodieTable.getActiveTimeline().deleteEmptyInstantIfExists(lastClean.get());
          } else {
            HoodieCleanMetadata cleanMetadata = TimelineMetadataUtils
                .deserializeHoodieCleanMetadata(hoodieTable.getActiveTimeline().getInstantDetails(lastClean.get()).get());
            if ((cleanMetadata.getEarliestCommitToRetain() != null)
                && (cleanMetadata.getEarliestCommitToRetain().length() > 0)) {
              return getPartitionPathsForIncrementalCleaning(cleanMetadata.getEarliestCommitToRetain(), instantToRetain);
            }
          }
        }
      }
    } else {
      // FILE_VERSIONS_RETAINED
      if (config.incrementalCleanerModeEnabled()) {
        // find the last completed commit from last clean and mark that as starting commit to clean
        Option<HoodieInstant> lastClean = hoodieTable.getCleanTimeline().filterCompletedInstants().lastInstant();
        if (lastClean.isPresent()) {
          if (hoodieTable.getActiveTimeline().isEmpty(lastClean.get())) {
            hoodieTable.getActiveTimeline().deleteEmptyInstantIfExists(lastClean.get());
          } else {
            try {
              HoodieCleanMetadata cleanMetadata = TimelineMetadataUtils
                  .deserializeHoodieCleanMetadata(hoodieTable.getActiveTimeline().getInstantDetails(lastClean.get()).get());
              if (!StringUtils.isNullOrEmpty(cleanMetadata.getLastCompletedCommitTimestamp())) {
                HoodieCleanMetadata finalCleanMetadata = cleanMetadata;
                Option<HoodieInstant> lastCompletedInstantDuringLastClean = hoodieTable.getActiveTimeline()
                    .filterCompletedInstants()
                    .filter(entry -> entry.getTimestamp().equals(finalCleanMetadata.getLastCompletedCommitTimestamp()))
                    .firstInstant();
                if (lastCompletedInstantDuringLastClean.isPresent()) {
                  return getPartitionPathsForIncrementalCleaning(lastCompletedInstantDuringLastClean.get().getTimestamp(), Option.empty());
                }
              }
            } catch (IOException e) {
              LOG.error("Failed to parse clean commit metadata for " + lastClean.get().toString());
            }
          }
        }
      }
    }
    return getPartitionPathsForFullCleaning();
  }

  /**
   * Use Incremental Mode for finding partition paths.
   * @param previousInstantRetained previous instant retained.
   * @param newInstantToRetain
   * @return
   */
  private List<String> getPartitionPathsForIncrementalCleaning(String previousInstantRetained,
      Option<HoodieInstant> newInstantToRetain) {
    LOG.info("Incremental Cleaning mode is enabled. Looking up partition-paths that have since changed "
        + "since last cleaned at " + previousInstantRetained
        + ". New Instant to retain : " + newInstantToRetain);

    Stream<HoodieInstant> filteredInstantsGreaterThan =  hoodieTable.getCompletedCommitsTimeline().getInstants().filter(
        instant -> HoodieTimeline.compareTimestamps(instant.getTimestamp(), HoodieTimeline.GREATER_THAN_OR_EQUALS,
            previousInstantRetained));
    Stream<HoodieInstant> filteredInstants = newInstantToRetain.isPresent()
        ? filteredInstantsGreaterThan.filter(instant -> HoodieTimeline.compareTimestamps(instant.getTimestamp(),
            HoodieTimeline.LESSER_THAN, newInstantToRetain.get().getTimestamp())) : filteredInstantsGreaterThan;

    return filteredInstants.flatMap(instant -> {
      try {
        if (HoodieTimeline.REPLACE_COMMIT_ACTION.equals(instant.getAction())) {
          HoodieReplaceCommitMetadata replaceCommitMetadata = HoodieReplaceCommitMetadata.fromBytes(
              hoodieTable.getActiveTimeline().getInstantDetails(instant).get(), HoodieReplaceCommitMetadata.class);
          return Stream.concat(replaceCommitMetadata.getPartitionToReplaceFileIds().keySet().stream(), replaceCommitMetadata.getPartitionToWriteStats().keySet().stream());
        } else {
          HoodieCommitMetadata commitMetadata = HoodieCommitMetadata
              .fromBytes(hoodieTable.getActiveTimeline().getInstantDetails(instant).get(),
                  HoodieCommitMetadata.class);
          return commitMetadata.getPartitionToWriteStats().keySet().stream();
        }
      } catch (IOException e) {
        throw new HoodieIOException(e.getMessage(), e);
      }
    }).distinct().collect(Collectors.toList());
  }

  /**
   * Scan and list all partitions for cleaning.
   * @return all partitions paths for the dataset.
   */
  private List<String> getPartitionPathsForFullCleaning() {
    // Go to brute force mode of scanning all partitions
    LOG.info("Getting partition paths for full cleaning");
    try {
      // Because the partition of BaseTableMetadata has been deleted,
      // all partition information can only be obtained from FileSystemBackedTableMetadata.
      FileSystemBackedTableMetadata fsBackedTableMetadata = new FileSystemBackedTableMetadata(context,
          context.getHadoopConf(), config.getBasePath(), config.shouldAssumeDatePartitioning());
      return fsBackedTableMetadata.getAllPartitionPaths();
    } catch (IOException e) {
      return Collections.emptyList();
    }
  }

  /**
   * Selects the older versions of files for cleaning, such that it bounds the number of versions of each file. This
   * policy is useful, if you are simply interested in querying the table, and you don't want too many versions for a
   * single file (i.e run it with versionsRetained = 1)
   */
  private Map<String, Pair<Boolean, List<CleanFileInfo>>> getFilesToCleanKeepingLatestVersions(List<String> partitionPaths) {
    LOG.info("Cleaning " + partitionPaths + ", retaining latest " + config.getCleanerFileVersionsRetained()
        + " file versions. ");
    Map<String, Pair<Boolean, List<CleanFileInfo>>> map = new HashMap<>();
    // Collect all the datafiles savepointed by all the savepoints
    List<String> savepointedFiles = hoodieTable.getSavepointTimestamps().stream()
        .flatMap(this::getSavepointedDataFiles)
        .collect(Collectors.toList());

    // In this scenario, we will assume that once replaced a file group automatically becomes eligible for cleaning completely
    // In other words, the file versions only apply to the active file groups.
    List<Pair<String, List<HoodieFileGroup>>> fileGroupsPerPartition = fileSystemView.getAllFileGroups(partitionPaths).collect(Collectors.toList());
    for (Pair<String, List<HoodieFileGroup>> partitionFileGroupList : fileGroupsPerPartition) {
      List<CleanFileInfo> deletePaths = new ArrayList<>(getReplacedFilesEligibleToClean(savepointedFiles, partitionFileGroupList.getLeft(), Option.empty()));
      boolean toDeletePartition = false;
      for (HoodieFileGroup fileGroup : partitionFileGroupList.getRight()) {
        int keepVersions = config.getCleanerFileVersionsRetained();
        // do not cleanup slice required for pending compaction
        Iterator<FileSlice> fileSliceIterator =
            fileGroup.getAllFileSlices()
                .filter(fs -> !isFileSliceNeededForPendingCompaction(fs))
                .iterator();
        if (isFileGroupInPendingCompaction(fileGroup)) {
          // We have already saved the last version of file-groups for pending compaction Id
          keepVersions--;
        }

        while (fileSliceIterator.hasNext() && keepVersions > 0) {
          // Skip this most recent version
          fileSliceIterator.next();
          keepVersions--;
        }
        // Delete the remaining files
        while (fileSliceIterator.hasNext()) {
          FileSlice nextSlice = fileSliceIterator.next();
          Option<HoodieBaseFile> dataFile = nextSlice.getBaseFile();
          if (dataFile.isPresent() && savepointedFiles.contains(dataFile.get().getFileName())) {
            // do not clean up a savepoint data file
            continue;
          }
          deletePaths.addAll(getCleanFileInfoForSlice(nextSlice));
        }
      }
      // if there are no valid file groups for the partition, mark it to be deleted
      if (partitionFileGroupList.getValue().isEmpty()) {
        toDeletePartition = true;
      }
      map.put(partitionFileGroupList.getLeft(), Pair.of(toDeletePartition, deletePaths));
    }
    return map;
  }

  private Map<String, Pair<Boolean, List<CleanFileInfo>>> getFilesToCleanKeepingLatestCommits(List<String> partitionPath) {
    return getFilesToCleanKeepingLatestCommits(partitionPath, config.getCleanerCommitsRetained(), HoodieCleaningPolicy.KEEP_LATEST_COMMITS);
  }

  /**
   * Selects the versions for file for cleaning, such that it
   * <p>
   * - Leaves the latest version of the file untouched - For older versions, - It leaves all the commits untouched which
   * has occurred in last <code>config.getCleanerCommitsRetained()</code> commits - It leaves ONE commit before this
   * window. We assume that the max(query execution time) == commit_batch_time * config.getCleanerCommitsRetained().
   * This is 5 hours by default (assuming ingestion is running every 30 minutes). This is essential to leave the file
   * used by the query that is running for the max time.
   * <p>
   * This provides the effect of having lookback into all changes that happened in the last X commits. (eg: if you
   * retain 10 commits, and commit batch time is 30 mins, then you have 5 hrs of lookback)
   * <p>
   * This policy is the default.
   *
   * @return A {@link Pair} whose left is boolean indicating whether partition itself needs to be deleted,
   *         and right is a list of {@link CleanFileInfo} about the files in the partition that needs to be deleted.
   */
  private Map<String, Pair<Boolean, List<CleanFileInfo>>> getFilesToCleanKeepingLatestCommits(List<String> partitionPaths, int commitsRetained, HoodieCleaningPolicy policy) {
    LOG.info("Cleaning " + partitionPaths + ", retaining latest " + commitsRetained + " commits. ");
    Map<String, Pair<Boolean, List<CleanFileInfo>>> cleanFileInfoPerPartitionMap = new HashMap<>();

    // Collect all the datafiles savepointed by all the savepoints
    List<String> savepointedFiles = hoodieTable.getSavepointTimestamps().stream()
        .flatMap(this::getSavepointedDataFiles)
        .collect(Collectors.toList());

    // determine if we have enough commits, to start cleaning.
    boolean toDeletePartition = false;
    if (commitTimeline.countInstants() > commitsRetained) {
      Option<HoodieInstant> earliestCommitToRetainOption = getEarliestCommitToRetain();
      HoodieInstant earliestCommitToRetain = earliestCommitToRetainOption.get();
      // add active files
      List<Pair<String, List<HoodieFileGroup>>> fileGroupsPerPartition = fileSystemView.getAllFileGroups(partitionPaths).collect(Collectors.toList());
      for (Pair<String, List<HoodieFileGroup>> partitionFileGroupList : fileGroupsPerPartition) {
        List<CleanFileInfo> deletePaths = new ArrayList<>(getReplacedFilesEligibleToClean(savepointedFiles, partitionFileGroupList.getLeft(), earliestCommitToRetainOption));
        // all replaced file groups before earliestCommitToRetain are eligible to clean
        deletePaths.addAll(getReplacedFilesEligibleToClean(savepointedFiles, partitionFileGroupList.getLeft(), earliestCommitToRetainOption));
        for (HoodieFileGroup fileGroup : partitionFileGroupList.getRight()) {
          List<FileSlice> fileSliceList = fileGroup.getAllFileSlices().collect(Collectors.toList());

          if (fileSliceList.isEmpty()) {
            continue;
          }

          String lastVersion = fileSliceList.get(0).getBaseInstantTime();
          String lastVersionBeforeEarliestCommitToRetain =
              getLatestVersionBeforeCommit(fileSliceList, earliestCommitToRetain);

          // Ensure there are more than 1 version of the file (we only clean old files from updates)
          // i.e always spare the last commit.
          for (FileSlice aSlice : fileSliceList) {
            Option<HoodieBaseFile> aFile = aSlice.getBaseFile();
            String fileCommitTime = aSlice.getBaseInstantTime();
            if (aFile.isPresent() && savepointedFiles.contains(aFile.get().getFileName())) {
              // do not clean up a savepoint data file
              continue;
            }

            if (policy == HoodieCleaningPolicy.KEEP_LATEST_COMMITS) {
              // Dont delete the latest commit and also the last commit before the earliest commit we
              // are retaining
              // The window of commit retain == max query run time. So a query could be running which
              // still
              // uses this file.
              if (fileCommitTime.equals(lastVersion) || (fileCommitTime.equals(lastVersionBeforeEarliestCommitToRetain))) {
                // move on to the next file
                continue;
              }
            } else if (policy == HoodieCleaningPolicy.KEEP_LATEST_BY_HOURS) {
              // This block corresponds to KEEP_LATEST_BY_HOURS policy
              // Do not delete the latest commit.
              if (fileCommitTime.equals(lastVersion)) {
                // move on to the next file
                continue;
              }
            }

            // Always keep the last commit
            if (!isFileSliceNeededForPendingCompaction(aSlice) && HoodieTimeline
                .compareTimestamps(earliestCommitToRetain.getTimestamp(), HoodieTimeline.GREATER_THAN, fileCommitTime)) {
              // this is a commit, that should be cleaned.
              aFile.ifPresent(hoodieDataFile -> {
                deletePaths.add(new CleanFileInfo(hoodieDataFile.getPath(), false));
                if (hoodieDataFile.getBootstrapBaseFile().isPresent() && config.shouldCleanBootstrapBaseFile()) {
                  deletePaths.add(new CleanFileInfo(hoodieDataFile.getBootstrapBaseFile().get().getPath(), true));
                }
              });
              if (hoodieTable.getMetaClient().getTableType() == HoodieTableType.MERGE_ON_READ
                  || hoodieTable.getMetaClient().getTableConfig().isCDCEnabled()) {
                // 1. If merge on read, then clean the log files for the commits as well;
                // 2. If change log capture is enabled, clean the log files no matter the table type is mor or cow.
                deletePaths.addAll(aSlice.getLogFiles().map(lf -> new CleanFileInfo(lf.getPath().toString(), false))
                    .collect(Collectors.toList()));
              }
            }
          }
        }
        // if there are no valid file groups for the partition, mark it to be deleted
        if (partitionFileGroupList.getValue().isEmpty()) {
          toDeletePartition = true;
        }
        cleanFileInfoPerPartitionMap.put(partitionFileGroupList.getLeft(), Pair.of(toDeletePartition, deletePaths));
      }
    }
    return cleanFileInfoPerPartitionMap;
  }

  /**
   * This method finds the files to be cleaned based on the number of hours. If {@code config.getCleanerHoursRetained()} is set to 5,
   * all the files with commit time earlier than 5 hours will be removed. Also the latest file for any file group is retained.
   * This policy gives much more flexibility to users for retaining data for running incremental queries as compared to
   * KEEP_LATEST_COMMITS cleaning policy. The default number of hours is 5.
   *
   * @param partitionPath partition path to check
   * @return list of files to clean
   */
  private Map<String, Pair<Boolean, List<CleanFileInfo>>> getFilesToCleanKeepingLatestHours(List<String> partitionPath) {
    return getFilesToCleanKeepingLatestCommits(partitionPath, 0, HoodieCleaningPolicy.KEEP_LATEST_BY_HOURS);
  }

  private List<CleanFileInfo> getReplacedFilesEligibleToClean(List<String> savepointedFiles, String partitionPath, Option<HoodieInstant> earliestCommitToRetain) {
    final Stream<HoodieFileGroup> replacedGroups;
    if (earliestCommitToRetain.isPresent()) {
      replacedGroups = fileSystemView.getReplacedFileGroupsBefore(earliestCommitToRetain.get().getTimestamp(), partitionPath);
    } else {
      replacedGroups = fileSystemView.getAllReplacedFileGroups(partitionPath);
    }
    return replacedGroups.flatMap(HoodieFileGroup::getAllFileSlices)
        // do not delete savepointed files  (archival will make sure corresponding replacecommit file is not deleted)
        .filter(slice -> !slice.getBaseFile().isPresent() || !savepointedFiles.contains(slice.getBaseFile().get().getFileName()))
        .flatMap(slice -> getCleanFileInfoForSlice(slice).stream())
        .collect(Collectors.toList());
  }

  /**
   * Gets the latest version < instantTime. This version file could still be used by queries.
   */
  private String getLatestVersionBeforeCommit(List<FileSlice> fileSliceList, HoodieInstant instantTime) {
    for (FileSlice file : fileSliceList) {
      String fileCommitTime = file.getBaseInstantTime();
      if (HoodieTimeline.compareTimestamps(instantTime.getTimestamp(), HoodieTimeline.GREATER_THAN, fileCommitTime)) {
        // fileList is sorted on the reverse, so the first commit we find <= instantTime is the
        // one we want
        return fileCommitTime;
      }
    }
    // There is no version of this file which is <= instantTime
    return null;
  }

  private List<CleanFileInfo> getCleanFileInfoForSlice(FileSlice nextSlice) {
    List<CleanFileInfo> cleanPaths = new ArrayList<>();
    if (nextSlice.getBaseFile().isPresent()) {
      HoodieBaseFile dataFile = nextSlice.getBaseFile().get();
      cleanPaths.add(new CleanFileInfo(dataFile.getPath(), false));
      if (dataFile.getBootstrapBaseFile().isPresent() && config.shouldCleanBootstrapBaseFile()) {
        cleanPaths.add(new CleanFileInfo(dataFile.getBootstrapBaseFile().get().getPath(), true));
      }
    }
    if (hoodieTable.getMetaClient().getTableType() == HoodieTableType.MERGE_ON_READ) {
      // If merge on read, then clean the log files for the commits as well
      Predicate<HoodieLogFile> notCDCLogFile =
          hoodieLogFile -> !hoodieLogFile.getFileName().endsWith(HoodieCDCUtils.CDC_LOGFILE_SUFFIX);
      cleanPaths.addAll(
          nextSlice.getLogFiles().filter(notCDCLogFile).map(lf -> new CleanFileInfo(lf.getPath().toString(), false))
              .collect(Collectors.toList()));
    }
    if (hoodieTable.getMetaClient().getTableConfig().isCDCEnabled()) {
      // The cdc log files will be written out in cdc scenario, no matter the table type is mor or cow.
      // Here we need to clean uo these cdc log files.
      Predicate<HoodieLogFile> isCDCLogFile =
          hoodieLogFile -> hoodieLogFile.getFileName().endsWith(HoodieCDCUtils.CDC_LOGFILE_SUFFIX);
      cleanPaths.addAll(
          nextSlice.getLogFiles().filter(isCDCLogFile).map(lf -> new CleanFileInfo(lf.getPath().toString(), false))
              .collect(Collectors.toList()));
    }
    return cleanPaths;
  }

  /**
   * Returns files to be cleaned for the given partitionPath based on cleaning policy.
   */
  public Map<String, Pair<Boolean, List<CleanFileInfo>>> getDeletePaths(List<String> partitionPaths) {
    HoodieCleaningPolicy policy = config.getCleanerPolicy();
    Map<String, Pair<Boolean, List<CleanFileInfo>>> deletePaths;
    if (policy == HoodieCleaningPolicy.KEEP_LATEST_COMMITS) {
      deletePaths = getFilesToCleanKeepingLatestCommits(partitionPaths);
    } else if (policy == HoodieCleaningPolicy.KEEP_LATEST_FILE_VERSIONS) {
      deletePaths = getFilesToCleanKeepingLatestVersions(partitionPaths);
    } else if (policy == HoodieCleaningPolicy.KEEP_LATEST_BY_HOURS) {
      deletePaths = getFilesToCleanKeepingLatestHours(partitionPaths);
    } else {
      throw new IllegalArgumentException("Unknown cleaning policy : " + policy.name());
    }
    for (String partitionPath : deletePaths.keySet()) {
      LOG.info(deletePaths.get(partitionPath).getRight().size() + " patterns used to delete in partition path:" + partitionPath);
      if (deletePaths.get(partitionPath).getLeft()) {
        LOG.info("Partition " + partitionPath + " to be deleted");
      }
    }
    return deletePaths;
  }

  /**
   * Returns earliest commit to retain based on cleaning policy.
   */
  public Option<HoodieInstant> getEarliestCommitToRetain() {
    Option<HoodieInstant> earliestCommitToRetain = Option.empty();
    int commitsRetained = config.getCleanerCommitsRetained();
    int hoursRetained = config.getCleanerHoursRetained();
    if (config.getCleanerPolicy() == HoodieCleaningPolicy.KEEP_LATEST_COMMITS
        && commitTimeline.countInstants() > commitsRetained) {
      earliestCommitToRetain = commitTimeline.nthInstant(commitTimeline.countInstants() - commitsRetained); //15 instants total, 10 commits to retain, this gives 6th instant in the list
    } else if (config.getCleanerPolicy() == HoodieCleaningPolicy.KEEP_LATEST_BY_HOURS) {
      Instant instant = Instant.now();
      ZonedDateTime currentDateTime = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
      String earliestTimeToRetain = HoodieActiveTimeline.formatDate(Date.from(currentDateTime.minusHours(hoursRetained).toInstant()));
      earliestCommitToRetain = Option.fromJavaOptional(commitTimeline.getInstants().filter(i -> HoodieTimeline.compareTimestamps(i.getTimestamp(),
              HoodieTimeline.GREATER_THAN_OR_EQUALS, earliestTimeToRetain)).findFirst());
    }
    return earliestCommitToRetain;
  }

  /**
   * Returns the last completed commit timestamp before clean.
   */
  public String getLastCompletedCommitTimestamp() {
    if (commitTimeline.lastInstant().isPresent()) {
      return commitTimeline.lastInstant().get().getTimestamp();
    } else {
      return "";
    }
  }

  /**
   * Determine if file slice needed to be preserved for pending compaction.
   *
   * @param fileSlice File Slice
   * @return true if file slice needs to be preserved, false otherwise.
   */
  private boolean isFileSliceNeededForPendingCompaction(FileSlice fileSlice) {
    CompactionOperation op = fgIdToPendingCompactionOperations.get(fileSlice.getFileGroupId());
    if (null != op) {
      // If file slice's instant time is newer or same as that of operation, do not clean
      return HoodieTimeline.compareTimestamps(fileSlice.getBaseInstantTime(), HoodieTimeline.GREATER_THAN_OR_EQUALS, op.getBaseInstantTime()
      );
    }
    return false;
  }

  private boolean isFileGroupInPendingCompaction(HoodieFileGroup fg) {
    return fgIdToPendingCompactionOperations.containsKey(fg.getFileGroupId());
  }
}
