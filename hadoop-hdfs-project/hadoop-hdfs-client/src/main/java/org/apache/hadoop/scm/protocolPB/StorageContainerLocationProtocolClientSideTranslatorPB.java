/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.scm.protocolPB;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.ipc.ProtobufHelper;
import org.apache.hadoop.ipc.ProtocolTranslator;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ozone.protocol.proto.OzoneProtos;
import org.apache.hadoop.scm.protocol.StorageContainerLocationProtocol;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.CloseContainerRequestProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.ContainerRequestProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.ContainerResponseProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.GetContainerRequestProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.GetContainerResponseProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.DeleteContainerRequestProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.ListContainerRequestProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.ListContainerResponseProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.NodeQueryRequestProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.NodeQueryResponseProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.NotifyObjectCreationStageRequestProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.PipelineRequestProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.PipelineResponseProto;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * This class is the client-side translator to translate the requests made on
 * the {@link StorageContainerLocationProtocol} interface to the RPC server
 * implementing {@link StorageContainerLocationProtocolPB}.
 */
@InterfaceAudience.Private
public final class StorageContainerLocationProtocolClientSideTranslatorPB
    implements StorageContainerLocationProtocol, ProtocolTranslator, Closeable {

  /**
   * RpcController is not used and hence is set to null.
   */
  private static final RpcController NULL_RPC_CONTROLLER = null;

  private final StorageContainerLocationProtocolPB rpcProxy;

  /**
   * Creates a new StorageContainerLocationProtocolClientSideTranslatorPB.
   *
   * @param rpcProxy {@link StorageContainerLocationProtocolPB} RPC proxy
   */
  public StorageContainerLocationProtocolClientSideTranslatorPB(
      StorageContainerLocationProtocolPB rpcProxy) {
    this.rpcProxy = rpcProxy;
  }

  /**
   * Asks SCM where a container should be allocated. SCM responds with the set
   * of datanodes that should be used creating this container. Ozone/SCM only
   * supports replication factor of either 1 or 3.
   * @param type - Replication Type
   * @param factor - Replication Count
   * @param containerName - Name
   * @return
   * @throws IOException
   */
  @Override
  public Pipeline allocateContainer(OzoneProtos.ReplicationType type,
      OzoneProtos.ReplicationFactor factor, String
      containerName) throws IOException {

    Preconditions.checkNotNull(containerName, "Container Name cannot be Null");
    Preconditions.checkState(!containerName.isEmpty(), "Container name cannot" +
        " be empty");
    ContainerRequestProto request = ContainerRequestProto.newBuilder()
        .setContainerName(containerName)
        .setReplicationFactor(factor)
        .setReplicationType(type)
        .build();

    final ContainerResponseProto response;
    try {
      response = rpcProxy.allocateContainer(NULL_RPC_CONTROLLER, request);
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
    if (response.getErrorCode() != ContainerResponseProto.Error.success) {
      throw new IOException(response.hasErrorMessage() ?
          response.getErrorMessage() : "Allocate container failed.");
    }
    return Pipeline.getFromProtoBuf(response.getPipeline());
  }

  public Pipeline getContainer(String containerName) throws IOException {
    Preconditions.checkNotNull(containerName,
        "Container Name cannot be Null");
    Preconditions.checkState(!containerName.isEmpty(),
        "Container name cannot be empty");
    GetContainerRequestProto request = GetContainerRequestProto
        .newBuilder()
        .setContainerName(containerName)
        .build();
    try {
      GetContainerResponseProto response =
          rpcProxy.getContainer(NULL_RPC_CONTROLLER, request);
      return Pipeline.getFromProtoBuf(response.getPipeline());
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<Pipeline> listContainer(String startName, String prefixName,
      int count) throws IOException {
    ListContainerRequestProto.Builder builder = ListContainerRequestProto
        .newBuilder();
    if (prefixName != null) {
      builder.setPrefixName(prefixName);
    }
    if (startName != null) {
      builder.setStartName(startName);
    }
    builder.setCount(count);
    ListContainerRequestProto request = builder.build();

    try {
      ListContainerResponseProto response =
          rpcProxy.listContainer(NULL_RPC_CONTROLLER, request);
      List<Pipeline> pipelineList = new ArrayList<>();
      for (OzoneProtos.Pipeline pipelineProto : response.getPipelineList()) {
        pipelineList.add(Pipeline.getFromProtoBuf(pipelineProto));
      }
      return pipelineList;
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  /**
   * Ask SCM to delete a container by name. SCM will remove
   * the container mapping in its database.
   *
   * @param containerName
   * @throws IOException
   */
  @Override
  public void deleteContainer(String containerName)
      throws IOException {
    Preconditions.checkState(!Strings.isNullOrEmpty(containerName),
        "Container name cannot be null or empty");
    DeleteContainerRequestProto request = DeleteContainerRequestProto
        .newBuilder()
        .setContainerName(containerName)
        .build();
    try {
      rpcProxy.deleteContainer(NULL_RPC_CONTROLLER, request);
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  /**
   * Queries a list of Node Statuses.
   *
   * @param nodeStatuses
   * @return List of Datanodes.
   */
  @Override
  public OzoneProtos.NodePool queryNode(EnumSet<OzoneProtos.NodeState>
      nodeStatuses, OzoneProtos.QueryScope queryScope, String poolName)
      throws IOException {
    // TODO : We support only cluster wide query right now. So ignoring checking
    // queryScope and poolName
    Preconditions.checkNotNull(nodeStatuses);
    Preconditions.checkState(nodeStatuses.size() > 0);
    NodeQueryRequestProto request = NodeQueryRequestProto.newBuilder()
        .addAllQuery(nodeStatuses)
        .setScope(queryScope).setPoolName(poolName).build();
    try {
      NodeQueryResponseProto response =
          rpcProxy.queryNode(NULL_RPC_CONTROLLER, request);
      return response.getDatanodes();
    } catch (ServiceException e) {
      throw  ProtobufHelper.getRemoteException(e);
    }

  }

  /**
   * Notify from client that creates object on datanodes.
   * @param type object type
   * @param name object name
   * @param stage object creation stage : begin/complete
   */
  @Override
  public void notifyObjectCreationStage(
      NotifyObjectCreationStageRequestProto.Type type,
      String name,
      NotifyObjectCreationStageRequestProto.Stage stage) throws IOException {
    Preconditions.checkState(!Strings.isNullOrEmpty(name),
        "Object name cannot be null or empty");
    NotifyObjectCreationStageRequestProto request =
        NotifyObjectCreationStageRequestProto.newBuilder()
            .setType(type)
            .setName(name)
            .setStage(stage)
            .build();
    try {
      rpcProxy.notifyObjectCreationStage(NULL_RPC_CONTROLLER, request);
    } catch(ServiceException e){
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  /**
   * Creates a replication pipeline of a specified type.
   *
   * @param replicationType - replication type
   * @param factor - factor 1 or 3
   * @param nodePool - optional machine list to build a pipeline.
   * @throws IOException
   */
  @Override
  public Pipeline createReplicationPipeline(OzoneProtos.ReplicationType
      replicationType, OzoneProtos.ReplicationFactor factor, OzoneProtos
      .NodePool nodePool) throws IOException {
    PipelineRequestProto request = PipelineRequestProto.newBuilder()
        .setNodePool(nodePool)
        .setReplicationFactor(factor)
        .setReplicationType(replicationType)
        .build();
    try {
      PipelineResponseProto response =
          rpcProxy.allocatePipeline(NULL_RPC_CONTROLLER, request);
      if (response.getErrorCode() ==
          PipelineResponseProto.Error.success) {
        Preconditions.checkState(response.hasPipeline(), "With success, " +
            "must come a pipeline");
        return Pipeline.getFromProtoBuf(response.getPipeline());
      } else {
        String errorMessage = String.format("create replication pipeline " +
                "failed. code : %s Message: %s", response.getErrorCode(),
            response.hasErrorMessage() ? response.getErrorMessage() : "");
        throw new IOException(errorMessage);
      }
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  public void closeContainer(String containerName) throws IOException {
    Preconditions.checkState(!Strings.isNullOrEmpty(containerName),
        "Container name cannot be null or empty");
    CloseContainerRequestProto request = CloseContainerRequestProto
        .newBuilder()
        .setContainerName(containerName)
        .build();
    try {
      rpcProxy.closeContainer(NULL_RPC_CONTROLLER, request);
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  public Object getUnderlyingProxyObject() {
    return rpcProxy;
  }

  @Override
  public void close() {
    RPC.stopProxy(rpcProxy);
  }
}