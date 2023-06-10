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

package org.apache.hudi.aws.sync;

import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.GlueCatalogSyncClientConfig;
import org.apache.hudi.hive.HiveSyncConfig;
import org.apache.hudi.sync.common.HoodieSyncClient;
import org.apache.hudi.sync.common.model.Partition;

import com.amazonaws.services.glue.AWSGlue;
import com.amazonaws.services.glue.AWSGlueClientBuilder;
import com.amazonaws.services.glue.model.AlreadyExistsException;
import com.amazonaws.services.glue.model.BatchCreatePartitionRequest;
import com.amazonaws.services.glue.model.BatchCreatePartitionResult;
import com.amazonaws.services.glue.model.BatchDeletePartitionRequest;
import com.amazonaws.services.glue.model.BatchDeletePartitionResult;
import com.amazonaws.services.glue.model.BatchUpdatePartitionRequest;
import com.amazonaws.services.glue.model.BatchUpdatePartitionRequestEntry;
import com.amazonaws.services.glue.model.BatchUpdatePartitionResult;
import com.amazonaws.services.glue.model.Column;
import com.amazonaws.services.glue.model.CreateDatabaseRequest;
import com.amazonaws.services.glue.model.CreateDatabaseResult;
import com.amazonaws.services.glue.model.CreatePartitionIndexRequest;
import com.amazonaws.services.glue.model.CreatePartitionIndexResult;
import com.amazonaws.services.glue.model.CreateTableRequest;
import com.amazonaws.services.glue.model.CreateTableResult;
import com.amazonaws.services.glue.model.DatabaseInput;
import com.amazonaws.services.glue.model.DeletePartitionIndexRequest;
import com.amazonaws.services.glue.model.EntityNotFoundException;
import com.amazonaws.services.glue.model.GetDatabaseRequest;
import com.amazonaws.services.glue.model.GetPartitionIndexesRequest;
import com.amazonaws.services.glue.model.GetPartitionIndexesResult;
import com.amazonaws.services.glue.model.GetPartitionsRequest;
import com.amazonaws.services.glue.model.GetPartitionsResult;
import com.amazonaws.services.glue.model.GetTableRequest;
import com.amazonaws.services.glue.model.PartitionIndex;
import com.amazonaws.services.glue.model.PartitionIndexDescriptor;
import com.amazonaws.services.glue.model.PartitionInput;
import com.amazonaws.services.glue.model.PartitionValueList;
import com.amazonaws.services.glue.model.ResourceNumberLimitExceededException;
import com.amazonaws.services.glue.model.SerDeInfo;
import com.amazonaws.services.glue.model.StorageDescriptor;
import com.amazonaws.services.glue.model.Table;
import com.amazonaws.services.glue.model.TableInput;
import com.amazonaws.services.glue.model.UpdateTableRequest;
import org.apache.parquet.schema.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.apache.hudi.aws.utils.S3Utils.s3aToS3;
import static org.apache.hudi.common.util.MapUtils.containsAll;
import static org.apache.hudi.common.util.MapUtils.isNullOrEmpty;
import static org.apache.hudi.hive.HiveSyncConfigHolder.HIVE_CREATE_MANAGED_TABLE;
import static org.apache.hudi.hive.HiveSyncConfigHolder.HIVE_SUPPORT_TIMESTAMP_TYPE;
import static org.apache.hudi.hive.util.HiveSchemaUtil.getPartitionKeyType;
import static org.apache.hudi.hive.util.HiveSchemaUtil.parquetSchemaToMapSchema;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_DATABASE_NAME;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_PARTITION_FIELDS;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_PARTITION_INDEX_FIELDS;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_PARTITION_INDEX_FIELDS_ENABLE;
import static org.apache.hudi.sync.common.util.TableUtils.tableId;

/**
 * This class implements all the AWS APIs to enable syncing of a Hudi Table with the
 * AWS Glue Data Catalog (https://docs.aws.amazon.com/glue/latest/dg/populate-data-catalog.html).
 *
 * @Experimental
 */
public class AWSGlueCatalogSyncClient extends HoodieSyncClient {

  private static final Logger  LOG                                 = LoggerFactory.getLogger(AWSGlueCatalogSyncClient.class);
  private static final int     MAX_PARTITIONS_PER_REQUEST          = 100;
  private static final long    BATCH_REQUEST_SLEEP_MILLIS          = 1000L;
  private static final String  GLUE_PARTITION_INDEX_ENABLE         = "partition_filtering.enabled";
  private static final long    INDEX_CREATION_REQUEST_SLEEP_MILLIS = 15_000L;
  private final        AWSGlue awsGlue;
  private final        String  databaseName;

  private final Boolean skipTableArchive;

  public AWSGlueCatalogSyncClient(HiveSyncConfig config) {
    super(config);
    this.awsGlue = AWSGlueClientBuilder.standard().build();
    this.databaseName = config.getStringOrDefault(META_SYNC_DATABASE_NAME);
    this.skipTableArchive = config.getBooleanOrDefault(GlueCatalogSyncClientConfig.GLUE_SKIP_TABLE_ARCHIVE);
  }

  @Override
  public List<Partition> getAllPartitions(String tableName) {
    try {
      List<Partition> partitions = new ArrayList<>();
      String nextToken = null;
      do {
        GetPartitionsResult result = awsGlue.getPartitions(new GetPartitionsRequest()
            .withDatabaseName(databaseName)
            .withTableName(tableName)
            .withNextToken(nextToken));
        partitions.addAll(result.getPartitions().stream()
                                .map(p -> new Partition(p.getValues(), p.getStorageDescriptor().getLocation()))
                                .collect(Collectors.toList()));
        nextToken = result.getNextToken();
      } while (nextToken != null);
      return partitions;
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Failed to get all partitions for table " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public void addPartitionsToTable(String tableName, List<String> partitionsToAdd) {
    if (partitionsToAdd.isEmpty()) {
      LOG.info("No partitions to add for " + tableId(databaseName, tableName));
      return;
    }
    LOG.info("Adding " + partitionsToAdd.size() + " partition(s) in table " + tableId(databaseName, tableName));
    try {
      Table table = getTable(awsGlue, databaseName, tableName);
      StorageDescriptor sd = table.getStorageDescriptor();
      List<PartitionInput> partitionInputs = partitionsToAdd.stream().map(partition -> {
        StorageDescriptor partitionSd = sd.clone();
        String fullPartitionPath = FSUtils.getPartitionPath(getBasePath(), partition).toString();
        List<String> partitionValues = partitionValueExtractor.extractPartitionValuesInPath(partition);
        partitionSd.setLocation(fullPartitionPath);
        return new PartitionInput().withValues(partitionValues).withStorageDescriptor(partitionSd);
      }).collect(Collectors.toList());

      for (List<PartitionInput> batch : CollectionUtils.batches(partitionInputs, MAX_PARTITIONS_PER_REQUEST)) {
        BatchCreatePartitionRequest request = new BatchCreatePartitionRequest();
        request.withDatabaseName(databaseName).withTableName(tableName).withPartitionInputList(batch);

        BatchCreatePartitionResult result = awsGlue.batchCreatePartition(request);
        if (CollectionUtils.nonEmpty(result.getErrors())) {
          if (result.getErrors().stream().allMatch((error) -> "AlreadyExistsException".equals(error.getErrorDetail().getErrorCode()))) {
            LOG.warn("Partitions already exist in glue: " + result.getErrors());
          } else {
            throw new HoodieGlueSyncException("Fail to add partitions to " + tableId(databaseName, tableName)
                + " with error(s): " + result.getErrors());
          }
        }
        Thread.sleep(BATCH_REQUEST_SLEEP_MILLIS);
      }
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to add partitions to " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public void updatePartitionsToTable(String tableName, List<String> changedPartitions) {
    if (changedPartitions.isEmpty()) {
      LOG.info("No partitions to change for " + tableName);
      return;
    }
    LOG.info("Updating " + changedPartitions.size() + "partition(s) in table " + tableId(databaseName, tableName));
    try {
      Table table = getTable(awsGlue, databaseName, tableName);
      StorageDescriptor sd = table.getStorageDescriptor();
      List<BatchUpdatePartitionRequestEntry> updatePartitionEntries = changedPartitions.stream().map(partition -> {
        StorageDescriptor partitionSd = sd.clone();
        String fullPartitionPath = FSUtils.getPartitionPath(getBasePath(), partition).toString();
        List<String> partitionValues = partitionValueExtractor.extractPartitionValuesInPath(partition);
        partitionSd.setLocation(fullPartitionPath);
        PartitionInput partitionInput = new PartitionInput().withValues(partitionValues).withStorageDescriptor(partitionSd);
        return new BatchUpdatePartitionRequestEntry().withPartitionInput(partitionInput).withPartitionValueList(partitionValues);
      }).collect(Collectors.toList());

      for (List<BatchUpdatePartitionRequestEntry> batch : CollectionUtils.batches(updatePartitionEntries, MAX_PARTITIONS_PER_REQUEST)) {
        BatchUpdatePartitionRequest request = new BatchUpdatePartitionRequest();
        request.withDatabaseName(databaseName).withTableName(tableName).withEntries(batch);

        BatchUpdatePartitionResult result = awsGlue.batchUpdatePartition(request);
        if (CollectionUtils.nonEmpty(result.getErrors())) {
          throw new HoodieGlueSyncException("Fail to update partitions to " + tableId(databaseName, tableName)
              + " with error(s): " + result.getErrors());
        }
        Thread.sleep(BATCH_REQUEST_SLEEP_MILLIS);
      }
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to update partitions to " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public void dropPartitions(String tableName, List<String> partitionsToDrop) {
    if (CollectionUtils.isNullOrEmpty(partitionsToDrop)) {
      LOG.info("No partitions to drop for " + tableName);
      return;
    }
    LOG.info("Drop " + partitionsToDrop.size() + "partition(s) in table " + tableId(databaseName, tableName));
    try {
      for (List<String> batch : CollectionUtils.batches(partitionsToDrop, MAX_PARTITIONS_PER_REQUEST)) {

        List<PartitionValueList> partitionValueLists = batch.stream().map(partition -> {
          PartitionValueList partitionValueList = new PartitionValueList();
          partitionValueList.setValues(partitionValueExtractor.extractPartitionValuesInPath(partition));
          return partitionValueList;
        }).collect(Collectors.toList());

        BatchDeletePartitionRequest batchDeletePartitionRequest = new BatchDeletePartitionRequest()
            .withDatabaseName(databaseName)
            .withTableName(tableName)
            .withPartitionsToDelete(partitionValueLists);

        BatchDeletePartitionResult result = awsGlue.batchDeletePartition(batchDeletePartitionRequest);
        if (CollectionUtils.nonEmpty(result.getErrors())) {
          throw new HoodieGlueSyncException("Fail to drop partitions to " + tableId(databaseName, tableName)
              + " with error(s): " + result.getErrors());
        }
        Thread.sleep(BATCH_REQUEST_SLEEP_MILLIS);
      }
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to drop partitions to " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public boolean updateTableProperties(String tableName, Map<String, String> tableProperties) {
    try {
      return updateTableParameters(awsGlue, databaseName, tableName, tableProperties, skipTableArchive);
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to update properties for table " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public void updateTableSchema(String tableName, MessageType newSchema) {
    // ToDo Cascade is set in Hive meta sync, but need to investigate how to configure it for Glue meta
    boolean cascade = config.getSplitStrings(META_SYNC_PARTITION_FIELDS).size() > 0;
    try {
      Table table = getTable(awsGlue, databaseName, tableName);
      Map<String, String> newSchemaMap = parquetSchemaToMapSchema(newSchema, config.getBoolean(HIVE_SUPPORT_TIMESTAMP_TYPE), false);
      List<Column> newColumns = getColumnsFromSchema(newSchemaMap);
      StorageDescriptor sd = table.getStorageDescriptor();
      sd.setColumns(newColumns);

      final Date now = new Date();
      TableInput updatedTableInput = new TableInput()
          .withName(tableName)
          .withTableType(table.getTableType())
          .withParameters(table.getParameters())
          .withPartitionKeys(table.getPartitionKeys())
          .withStorageDescriptor(sd)
          .withLastAccessTime(now)
          .withLastAnalyzedTime(now);

      UpdateTableRequest request = new UpdateTableRequest()
          .withDatabaseName(databaseName)
          .withSkipArchive(skipTableArchive)
          .withTableInput(updatedTableInput);

      awsGlue.updateTable(request);
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to update definition for table " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public void createTable(String tableName,
                          MessageType storageSchema,
                          String inputFormatClass,
                          String outputFormatClass,
                          String serdeClass,
                          Map<String, String> serdeProperties,
                          Map<String, String> tableProperties) {
    if (tableExists(tableName)) {
      return;
    }
    CreateTableRequest request = new CreateTableRequest();
    Map<String, String> params = new HashMap<>();
    if (!config.getBoolean(HIVE_CREATE_MANAGED_TABLE)) {
      params.put("EXTERNAL", "TRUE");
    }
    params.putAll(tableProperties);

    try {
      Map<String, String> mapSchema = parquetSchemaToMapSchema(storageSchema, config.getBoolean(HIVE_SUPPORT_TIMESTAMP_TYPE), false);

      List<Column> schemaWithoutPartitionKeys = getColumnsFromSchema(mapSchema);

      // now create the schema partition
      List<Column> schemaPartitionKeys = config.getSplitStrings(META_SYNC_PARTITION_FIELDS).stream().map(partitionKey -> {
        String keyType = getPartitionKeyType(mapSchema, partitionKey);
        return new Column().withName(partitionKey).withType(keyType.toLowerCase()).withComment("");
      }).collect(Collectors.toList());

      StorageDescriptor storageDescriptor = new StorageDescriptor();
      serdeProperties.put("serialization.format", "1");
      storageDescriptor
          .withSerdeInfo(new SerDeInfo().withSerializationLibrary(serdeClass).withParameters(serdeProperties))
          .withLocation(s3aToS3(getBasePath()))
          .withInputFormat(inputFormatClass)
          .withOutputFormat(outputFormatClass)
          .withColumns(schemaWithoutPartitionKeys);

      final Date now = new Date();
      TableInput tableInput = new TableInput()
          .withName(tableName)
          .withTableType(TableType.EXTERNAL_TABLE.toString())
          .withParameters(params)
          .withPartitionKeys(schemaPartitionKeys)
          .withStorageDescriptor(storageDescriptor)
          .withLastAccessTime(now)
          .withLastAnalyzedTime(now);
      request.withDatabaseName(databaseName)
          .withTableInput(tableInput);

      CreateTableResult result = awsGlue.createTable(request);
      LOG.info("Created table " + tableId(databaseName, tableName) + " : " + result);
    } catch (AlreadyExistsException e) {
      LOG.warn("Table " + tableId(databaseName, tableName) + " already exists.", e);
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to create " + tableId(databaseName, tableName), e);
    }
  }

  /**
   * This will manage partitions indexes. Users can activate/deactivate them on existing tables.
   * Removing index definition, will result in dropping the index.
   * <p>
   * reference doc for partition indexes:
   * https://docs.aws.amazon.com/glue/latest/dg/partition-indexes.html#partition-index-getpartitions
   *
   * @param tableName
   */
  public void managePartitionIndexes(String tableName) throws InterruptedException {
    if (!config.getBooleanOrDefault(META_SYNC_PARTITION_INDEX_FIELDS_ENABLE)) {
      // deactivate indexing if enabled
      if (getPartitionIndexEnable(tableName)) {
        LOG.warn("Deactivating partition indexing");
        updatePartitionIndexEnable(tableName, false);
      }

      // also drop all existing indexes
      GetPartitionIndexesRequest indexesRequest = new GetPartitionIndexesRequest()
          .withDatabaseName(databaseName).withTableName(tableName);
      GetPartitionIndexesResult existingIdxs = awsGlue.getPartitionIndexes(indexesRequest);
      existingIdxs.getPartitionIndexDescriptorList().forEach(existingIdx -> {
        LOG.warn("Dropping partition index: " + existingIdx.getIndexName());
        DeletePartitionIndexRequest idxToDelete = new DeletePartitionIndexRequest()
            .withDatabaseName(databaseName).withTableName(tableName).withIndexName(existingIdx.getIndexName());
        awsGlue.deletePartitionIndex(idxToDelete);
      });
    } else {
      // activate indexing usage if disabled
      if (!getPartitionIndexEnable(tableName)) {
        LOG.warn("Activating partition indexing");
        updatePartitionIndexEnable(tableName, true);
      }

      // get indexes to be created
      List<List<String>> partitionsIndexNeeded = parsePartitionsIndexConfig();
      // get existing indexes
      GetPartitionIndexesRequest indexesRequest = new GetPartitionIndexesRequest()
          .withDatabaseName(databaseName).withTableName(tableName);
      GetPartitionIndexesResult existingIdxs = awsGlue.getPartitionIndexes(indexesRequest);

      // for each existing index
      // remove if not relevant anymore
      existingIdxs.getPartitionIndexDescriptorList().forEach(existingIdx -> {
        List<String> idxColumns = existingIdx.getKeys().stream().map(key -> key.getName()).collect(Collectors.toList());
        Boolean toBeRemoved = true;
        for (List<String> neededIdx : partitionsIndexNeeded) {
          if (neededIdx.equals(idxColumns)) {
            toBeRemoved = false;
          }
        }
        if (toBeRemoved) {
          DeletePartitionIndexRequest idxToDelete = new DeletePartitionIndexRequest()
              .withDatabaseName(databaseName).withTableName(tableName).withIndexName(existingIdx.getIndexName());
          LOG.warn("Dropping irrelevant index: " + existingIdx.getIndexName());
          awsGlue.deletePartitionIndex(idxToDelete);
        }
      });

      // for each needed index
      // create if not exist
      for (List<String> neededIdx : partitionsIndexNeeded) {
        Boolean toBeCreated = true;
        for (PartitionIndexDescriptor existingIdx : existingIdxs.getPartitionIndexDescriptorList()) {
          List<String> collect = existingIdx.getKeys().stream().map(key -> key.getName()).collect(Collectors.toList());
          if (collect.equals(neededIdx)) {
            toBeCreated = false;
          }
        }
        if (toBeCreated) {
          String newIdxName = String.format("hudi_managed_index_%s", neededIdx.toString());
          PartitionIndex newIdx = new PartitionIndex()
              .withIndexName(newIdxName)
              .withKeys(neededIdx);
          LOG.warn("Creating new partition index: " + newIdxName);
          CreatePartitionIndexRequest creationRequest = new CreatePartitionIndexRequest()
              .withDatabaseName(databaseName).withTableName(tableName).withPartitionIndex(newIdx);
          // now create indexes one by one
          // until an index is not created subsequent call will raise an exception
          while (true) {
            try {
              awsGlue.createPartitionIndex(creationRequest);
              break;
            } catch (ResourceNumberLimitExceededException e) {
              LOG.warn("Waiting until the indexation process is done...");
              Thread.sleep(INDEX_CREATION_REQUEST_SLEEP_MILLIS);
            }
          }
        }
      }
    }
  }

  protected List<List<String>> parsePartitionsIndexConfig() {
    String rawPartitionIndex = config.getStringOrDefault(META_SYNC_PARTITION_INDEX_FIELDS);
    List<List<String>> indexes = Arrays.stream(rawPartitionIndex.split(";"))
                                       .map(idx -> Arrays.stream(idx.split(","))
                                                         .collect(Collectors.toList())).collect(Collectors.toList());
    return indexes;
  }

  public Boolean getPartitionIndexEnable(String tableName) {
    try {
      Table table = getTable(awsGlue, databaseName, tableName);
      return Boolean.valueOf(table.getParameters().get(GLUE_PARTITION_INDEX_ENABLE));
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to get parameter " + GLUE_PARTITION_INDEX_ENABLE + " time for " + tableId(databaseName, tableName), e);
    }
  }

  public void updatePartitionIndexEnable(String tableName, Boolean enable) {
    try {
      updateTableParameters(awsGlue, databaseName, tableName, Collections.singletonMap(GLUE_PARTITION_INDEX_ENABLE, enable.toString()), false);
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to update parameter " + GLUE_PARTITION_INDEX_ENABLE + " time for " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public Map<String, String> getMetastoreSchema(String tableName) {
    try {
      // GlueMetastoreClient returns partition keys separate from Columns, hence get both and merge to
      // get the Schema of the table.
      Table table = getTable(awsGlue, databaseName, tableName);
      Map<String, String> partitionKeysMap =
          table.getPartitionKeys().stream().collect(Collectors.toMap(Column::getName, f -> f.getType().toUpperCase()));

      Map<String, String> columnsMap =
          table.getStorageDescriptor().getColumns().stream().collect(Collectors.toMap(Column::getName, f -> f.getType().toUpperCase()));

      Map<String, String> schema = new HashMap<>();
      schema.putAll(columnsMap);
      schema.putAll(partitionKeysMap);
      return schema;
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to get schema for table " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public boolean tableExists(String tableName) {
    GetTableRequest request = new GetTableRequest()
        .withDatabaseName(databaseName)
        .withName(tableName);
    try {
      return Objects.nonNull(awsGlue.getTable(request).getTable());
    } catch (EntityNotFoundException e) {
      LOG.info("Table not found: " + tableId(databaseName, tableName), e);
      return false;
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to get table: " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public boolean databaseExists(String databaseName) {
    GetDatabaseRequest request = new GetDatabaseRequest();
    request.setName(databaseName);
    try {
      return Objects.nonNull(awsGlue.getDatabase(request).getDatabase());
    } catch (EntityNotFoundException e) {
      LOG.info("Database not found: " + databaseName, e);
      return false;
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to check if database exists " + databaseName, e);
    }
  }

  @Override
  public void createDatabase(String databaseName) {
    if (databaseExists(databaseName)) {
      return;
    }
    CreateDatabaseRequest request = new CreateDatabaseRequest();
    request.setDatabaseInput(new DatabaseInput()
        .withName(databaseName)
        .withDescription("Automatically created by " + this.getClass().getName())
        .withParameters(null)
        .withLocationUri(null));
    try {
      CreateDatabaseResult result = awsGlue.createDatabase(request);
      LOG.info("Successfully created database in AWS Glue: " + result.toString());
    } catch (AlreadyExistsException e) {
      LOG.warn("AWS Glue Database " + databaseName + " already exists", e);
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to create database " + databaseName, e);
    }
  }

  @Override
  public Option<String> getLastCommitTimeSynced(String tableName) {
    try {
      Table table = getTable(awsGlue, databaseName, tableName);
      return Option.ofNullable(table.getParameters().get(HOODIE_LAST_COMMIT_TIME_SYNC));
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to get last sync commit time for " + tableId(databaseName, tableName), e);
    }
  }

  @Override
  public void close() {
    awsGlue.shutdown();
  }

  @Override
  public void updateLastCommitTimeSynced(String tableName) {
    if (!getActiveTimeline().lastInstant().isPresent()) {
      LOG.warn("No commit in active timeline.");
      return;
    }
    final String lastCommitTimestamp = getActiveTimeline().lastInstant().get().getTimestamp();
    try {
      updateTableParameters(awsGlue, databaseName, tableName, Collections.singletonMap(HOODIE_LAST_COMMIT_TIME_SYNC, lastCommitTimestamp), skipTableArchive);
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to update last sync commit time for " + tableId(databaseName, tableName), e);
    }
    try {
      // as a side effect, we also refresh the partition indexes if needed
      // people may wan't to add indexes, without re-creating the table
      // therefore we call this at each commit as a workaround
      managePartitionIndexes(tableName);
    } catch (Exception e) {
      LOG.warn("Something went wrong with partition index", e);
    }
  }

  @Override
  public Option<String> getLastReplicatedTime(String tableName) {
    throw new UnsupportedOperationException("Not supported: `getLastReplicatedTime`");
  }

  @Override
  public void updateLastReplicatedTimeStamp(String tableName, String timeStamp) {
    throw new UnsupportedOperationException("Not supported: `updateLastReplicatedTimeStamp`");
  }

  @Override
  public void deleteLastReplicatedTimeStamp(String tableName) {
    throw new UnsupportedOperationException("Not supported: `deleteLastReplicatedTimeStamp`");
  }

  private List<Column> getColumnsFromSchema(Map<String, String> mapSchema) {
    List<Column> cols = new ArrayList<>();
    for (String key : mapSchema.keySet()) {
      // In Glue, the full schema should exclude the partition keys
      if (!config.getSplitStrings(META_SYNC_PARTITION_FIELDS).contains(key)) {
        String keyType = getPartitionKeyType(mapSchema, key);
        Column column = new Column().withName(key).withType(keyType.toLowerCase()).withComment("");
        cols.add(column);
      }
    }
    return cols;
  }

  private enum TableType {
    MANAGED_TABLE,
    EXTERNAL_TABLE,
    VIRTUAL_VIEW,
    INDEX_TABLE,
    MATERIALIZED_VIEW
  }

  private static Table getTable(AWSGlue awsGlue, String databaseName, String tableName) throws HoodieGlueSyncException {
    GetTableRequest request = new GetTableRequest()
        .withDatabaseName(databaseName)
        .withName(tableName);
    try {
      return awsGlue.getTable(request).getTable();
    } catch (EntityNotFoundException e) {
      throw new HoodieGlueSyncException("Table not found: " + tableId(databaseName, tableName), e);
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to get table " + tableId(databaseName, tableName), e);
    }
  }

  private static boolean updateTableParameters(AWSGlue awsGlue, String databaseName, String tableName, Map<String, String> updatingParams, boolean skipTableArchive) {
    if (isNullOrEmpty(updatingParams)) {
      return false;
    }
    try {
      Table table = getTable(awsGlue, databaseName, tableName);
      Map<String, String> remoteParams = table.getParameters();
      if (containsAll(remoteParams, updatingParams)) {
        return false;
      }

      final Map<String, String> newParams = new HashMap<>();
      newParams.putAll(table.getParameters());
      newParams.putAll(updatingParams);

      final Date now = new Date();
      TableInput updatedTableInput = new TableInput()
          .withName(tableName)
          .withTableType(table.getTableType())
          .withParameters(newParams)
          .withPartitionKeys(table.getPartitionKeys())
          .withStorageDescriptor(table.getStorageDescriptor())
          .withLastAccessTime(now)
          .withLastAnalyzedTime(now);

      UpdateTableRequest request = new UpdateTableRequest();
      request.withDatabaseName(databaseName)
          .withSkipArchive(skipTableArchive)
          .withTableInput(updatedTableInput);
      awsGlue.updateTable(request);
      return true;
    } catch (Exception e) {
      throw new HoodieGlueSyncException("Fail to update params for table " + tableId(databaseName, tableName) + ": " + updatingParams, e);
    }
  }
}
