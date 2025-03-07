/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.server.executor.service;

import com.google.auto.service.AutoService;
import io.dingodb.cluster.ClusterServiceProvider;
import io.dingodb.common.Location;
import io.dingodb.common.concurrent.Executors;
import io.dingodb.common.config.DingoConfiguration;
import io.dingodb.sdk.service.CoordinatorService;
import io.dingodb.sdk.service.Services;
import io.dingodb.sdk.service.entity.common.Executor;
import io.dingodb.sdk.service.entity.common.ExecutorState;
import io.dingodb.sdk.service.entity.common.ExecutorUser;
import io.dingodb.sdk.service.entity.coordinator.ExecutorHeartbeatRequest;
import io.dingodb.sdk.service.entity.coordinator.GetExecutorMapRequest;
import io.dingodb.server.executor.Configuration;
import io.dingodb.tso.TsoService;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class ClusterService implements io.dingodb.cluster.ClusterService {

    public static final ClusterService DEFAULT_INSTANCE = new ClusterService();

    @AutoService(ClusterServiceProvider.class)
    public static final class Provider implements ClusterServiceProvider {
        @Override
        public io.dingodb.cluster.ClusterService get() {
            return DEFAULT_INSTANCE;
        }
    }

    private ClusterService() {
    }

    //
    // Cluster service.
    //
    public static final CoordinatorService coordinatorService = Services.coordinatorService(
        Services.parse(Configuration.coordinators())
    );

    public static final Executor executor = Executor.builder()
        .serverLocation(io.dingodb.sdk.service.entity.common.Location.builder()
            .host(DingoConfiguration.host())
            .port(DingoConfiguration.port())
            .build())
        .executorUser(ExecutorUser.builder()
            .user(Configuration.user())
            .keyring(Configuration.keyring())
            .build())
        .resourceTag(Configuration.resourceTag())
        .build();
    public static final ExecutorHeartbeatRequest executorHeartbeatRequest = ExecutorHeartbeatRequest.builder()
        .selfExecutormapEpoch(0)
        .executor(executor)
        .build();

    @Override
    public List<Location> getComputingLocations() {
        return coordinatorService.getExecutorMap(
                TsoService.getDefault().tso(), GetExecutorMapRequest.builder().build()
            ).getExecutormap().getExecutors().stream()
            .filter($ -> $.getState() == ExecutorState.EXECUTOR_NORMAL)
            .map(io.dingodb.sdk.service.entity.common.Executor::getServerLocation)
            .map($ -> new Location($.getHost(), $.getPort()))
            .collect(Collectors.toList());
    }

    public static void register() {
        Executors.scheduleWithFixedDelayAsync(
            "cluster-heartbeat",
            () -> coordinatorService.executorHeartbeat(TsoService.getDefault().tso(), executorHeartbeatRequest),
            0,
            1,
            TimeUnit.SECONDS
        );
    }
}
