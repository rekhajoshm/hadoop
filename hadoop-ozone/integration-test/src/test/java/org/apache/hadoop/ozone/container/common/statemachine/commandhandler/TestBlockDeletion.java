/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.container.common.statemachine.commandhandler;

import com.google.common.primitives.Longs;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.container.common.helpers.ContainerData;
import org.apache.hadoop.ozone.container.common.helpers.KeyUtils;
import org.apache.hadoop.ozone.container.common.impl.ContainerManagerImpl;
import org.apache.hadoop.ozone.ksm.KeySpaceManager;
import org.apache.hadoop.ozone.ksm.helpers.KsmKeyArgs;
import org.apache.hadoop.ozone.ksm.helpers.KsmKeyLocationInfo;
import org.apache.hadoop.ozone.ksm.helpers.KsmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.ozShell.TestOzoneShell;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.utils.MetadataStore;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_BLOCK_DELETING_SERVICE_INTERVAL;

public class TestBlockDeletion {
  private static OzoneConfiguration conf = null;
  private static ObjectStore store;
  private static ContainerManagerImpl dnContainerManager = null;
  private static StorageContainerManager scm = null;
  private static KeySpaceManager ksm = null;
  private static Set<Long> containerIdsWithDeletedBlocks;

  @BeforeClass
  public static void init() throws Exception {
    conf = new OzoneConfiguration();

    String path =
        GenericTestUtils.getTempPath(TestOzoneShell.class.getSimpleName());
    File baseDir = new File(path);
    baseDir.mkdirs();

    path += conf.getTrimmed(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT,
        OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT_DEFAULT);

    conf.set(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT, path);
    conf.setQuietMode(false);
    conf.setTimeDuration(OZONE_BLOCK_DELETING_SERVICE_INTERVAL, 100,
        TimeUnit.MILLISECONDS);

    MiniOzoneCluster cluster =
        MiniOzoneCluster.newBuilder(conf).setNumDatanodes(1).build();
    cluster.waitForClusterToBeReady();
    store = OzoneClientFactory.getRpcClient(conf).getObjectStore();
    dnContainerManager =
        (ContainerManagerImpl) cluster.getHddsDatanodes().get(0)
            .getDatanodeStateMachine().getContainer().getContainerManager();
    ksm = cluster.getKeySpaceManager();
    scm = cluster.getStorageContainerManager();
    containerIdsWithDeletedBlocks = new HashSet<>();
  }

  @Test(timeout = 60000)
  public void testBlockDeletion()
      throws IOException, InterruptedException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    String value = RandomStringUtils.random(1000000);
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    String keyName = UUID.randomUUID().toString();

    OzoneOutputStream out = bucket.createKey(keyName, value.getBytes().length,
        ReplicationType.STAND_ALONE, ReplicationFactor.ONE);
    out.write(value.getBytes());
    out.close();

    KsmKeyArgs keyArgs = new KsmKeyArgs.Builder().setVolumeName(volumeName)
        .setBucketName(bucketName).setKeyName(keyName).setDataSize(0)
        .setType(HddsProtos.ReplicationType.STAND_ALONE)
        .setFactor(HddsProtos.ReplicationFactor.ONE).build();
    List<KsmKeyLocationInfoGroup> ksmKeyLocationInfoGroupList =
        ksm.lookupKey(keyArgs).getKeyLocationVersions();

    // verify key blocks were created in DN.
    Assert.assertTrue(verifyBlocksCreated(ksmKeyLocationInfoGroupList));
    // No containers with deleted blocks
    Assert.assertTrue(containerIdsWithDeletedBlocks.isEmpty());
    // Delete transactionIds for the containers should be 0
    matchContainerTransactionIds();
    ksm.deleteKey(keyArgs);
    Thread.sleep(5000);
    // The blocks should be deleted in the DN.
    Assert.assertTrue(verifyBlocksDeleted(ksmKeyLocationInfoGroupList));

    // Few containers with deleted blocks
    Assert.assertTrue(!containerIdsWithDeletedBlocks.isEmpty());
    // Containers in the DN and SCM should have same delete transactionIds
    matchContainerTransactionIds();
  }

  private void matchContainerTransactionIds() throws IOException {
    List<ContainerData> containerDataList = new ArrayList<>();
    dnContainerManager.listContainer(0, 10000, containerDataList);
    for (ContainerData containerData : containerDataList) {
      long containerId = containerData.getContainerID();
      if (containerIdsWithDeletedBlocks.contains(containerId)) {
        Assert.assertTrue(
            scm.getContainerInfo(containerId).getDeleteTransactionId() > 0);
      } else {
        Assert.assertEquals(
            scm.getContainerInfo(containerId).getDeleteTransactionId(), 0);
      }
      Assert.assertEquals(dnContainerManager.readContainer(containerId)
              .getDeleteTransactionId(),
          scm.getContainerInfo(containerId).getDeleteTransactionId());
    }
  }

  private boolean verifyBlocksCreated(
      List<KsmKeyLocationInfoGroup> ksmKeyLocationInfoGroups)
      throws IOException {
    return performOperationOnKeyContainers((blockID) -> {
      try {
        MetadataStore db = KeyUtils.getDB(
            dnContainerManager.getContainerMap().get(blockID.getContainerID()),
            conf);
        Assert.assertNotNull(db.get(Longs.toByteArray(blockID.getLocalID())));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }, ksmKeyLocationInfoGroups);
  }

  private boolean verifyBlocksDeleted(
      List<KsmKeyLocationInfoGroup> ksmKeyLocationInfoGroups)
      throws IOException {
    return performOperationOnKeyContainers((blockID) -> {
      try {
        MetadataStore db = KeyUtils.getDB(
            dnContainerManager.getContainerMap().get(blockID.getContainerID()),
            conf);
        Assert.assertNull(db.get(Longs.toByteArray(blockID.getLocalID())));
        Assert.assertNull(db.get(DFSUtil.string2Bytes(
            OzoneConsts.DELETING_KEY_PREFIX + blockID.getLocalID())));
        Assert.assertNotNull(DFSUtil.string2Bytes(
            OzoneConsts.DELETED_KEY_PREFIX + blockID.getLocalID()));
        containerIdsWithDeletedBlocks.add(blockID.getContainerID());
      } catch (IOException e) {
        e.printStackTrace();
      }
    }, ksmKeyLocationInfoGroups);
  }

  private boolean performOperationOnKeyContainers(Consumer<BlockID> consumer,
      List<KsmKeyLocationInfoGroup> ksmKeyLocationInfoGroups)
      throws IOException {

    try {
      for (KsmKeyLocationInfoGroup ksmKeyLocationInfoGroup : ksmKeyLocationInfoGroups) {
        List<KsmKeyLocationInfo> ksmKeyLocationInfos =
            ksmKeyLocationInfoGroup.getLocationList();
        for (KsmKeyLocationInfo ksmKeyLocationInfo : ksmKeyLocationInfos) {
          BlockID blockID = ksmKeyLocationInfo.getBlockID();
          consumer.accept(blockID);
        }
      }
    } catch (Error e) {
      e.printStackTrace();
      return false;
    }
    return true;
  }
}