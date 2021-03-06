/**
 * Copyright © 2016 The Thingsboard Authors
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
package org.thingsboard.server.extensions.core.plugin.telemetry.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.kv.*;
import org.thingsboard.server.extensions.api.plugins.PluginCallback;
import org.thingsboard.server.extensions.api.plugins.PluginContext;
import org.thingsboard.server.extensions.api.plugins.handlers.DefaultWebsocketMsgHandler;
import org.thingsboard.server.extensions.api.plugins.ws.PluginWebsocketSessionRef;
import org.thingsboard.server.extensions.api.plugins.ws.WsSessionMetaData;
import org.thingsboard.server.extensions.api.plugins.ws.msg.BinaryPluginWebSocketMsg;
import org.thingsboard.server.extensions.api.plugins.ws.msg.PluginWebsocketMsg;
import org.thingsboard.server.extensions.api.plugins.ws.msg.TextPluginWebSocketMsg;
import org.thingsboard.server.extensions.core.plugin.telemetry.SubscriptionManager;
import org.thingsboard.server.extensions.core.plugin.telemetry.cmd.*;
import org.thingsboard.server.extensions.core.plugin.telemetry.sub.SubscriptionErrorCode;
import org.thingsboard.server.extensions.core.plugin.telemetry.sub.SubscriptionState;
import org.thingsboard.server.extensions.core.plugin.telemetry.sub.SubscriptionType;
import org.thingsboard.server.extensions.core.plugin.telemetry.sub.SubscriptionUpdate;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Andrew Shvayka
 */
@Slf4j
public class TelemetryWebsocketMsgHandler extends DefaultWebsocketMsgHandler {

    private static final int UNKNOWN_SUBSCRIPTION_ID = 0;

    private final SubscriptionManager subscriptionManager;

    public TelemetryWebsocketMsgHandler(SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    @Override
    protected void handleWebSocketMsg(PluginContext ctx, PluginWebsocketSessionRef sessionRef, PluginWebsocketMsg<?> wsMsg) {
        try {
            TelemetryPluginCmdsWrapper cmdsWrapper = null;
            if (wsMsg instanceof TextPluginWebSocketMsg) {
                TextPluginWebSocketMsg textMsg = (TextPluginWebSocketMsg) wsMsg;
                cmdsWrapper = jsonMapper.readValue(textMsg.getPayload(), TelemetryPluginCmdsWrapper.class);
            } else if (wsMsg instanceof BinaryPluginWebSocketMsg) {
                throw new IllegalStateException("Not Implemented!");
                // TODO: add support of BSON here based on
                // https://github.com/michel-kraemer/bson4jackson
            }
            if (cmdsWrapper != null) {
                if (cmdsWrapper.getAttrSubCmds() != null) {
                    cmdsWrapper.getAttrSubCmds().forEach(cmd -> handleWsAttributesSubscriptionCmd(ctx, sessionRef, cmd));
                }
                if (cmdsWrapper.getTsSubCmds() != null) {
                    cmdsWrapper.getTsSubCmds().forEach(cmd -> handleWsTimeseriesSubscriptionCmd(ctx, sessionRef, cmd));
                }
                if (cmdsWrapper.getHistoryCmds() != null) {
                    cmdsWrapper.getHistoryCmds().forEach(cmd -> handleWsHistoryCmd(ctx, sessionRef, cmd));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to decode subscription cmd: {}", e.getMessage(), e);
            SubscriptionUpdate update = new SubscriptionUpdate(UNKNOWN_SUBSCRIPTION_ID, SubscriptionErrorCode.INTERNAL_ERROR,
                    "Session meta-data not found!");
            sendWsMsg(ctx, sessionRef, update);
        }
    }

    @Override
    protected void cleanupWebSocketSession(PluginContext ctx, String sessionId) {
        subscriptionManager.cleanupLocalWsSessionSubscriptions(ctx, sessionId);
    }

    private void handleWsAttributesSubscriptionCmd(PluginContext ctx, PluginWebsocketSessionRef sessionRef, AttributesSubscriptionCmd cmd) {
        String sessionId = sessionRef.getSessionId();
        log.debug("[{}] Processing: {}", sessionId, cmd);

        if (validateSessionMetadata(ctx, sessionRef, cmd, sessionId)) {
            if (cmd.isUnsubscribe()) {
                unsubscribe(ctx, cmd, sessionId);
            } else if (validateSubscriptionCmd(ctx, sessionRef, cmd)) {
                log.debug("[{}] fetching latest attributes ({}) values for device: {}", sessionId, cmd.getKeys(), cmd.getDeviceId());
                DeviceId deviceId = DeviceId.fromString(cmd.getDeviceId());
                Optional<Set<String>> keysOptional = getKeys(cmd);
                SubscriptionState sub;
                if (keysOptional.isPresent()) {
                    List<String> keys = new ArrayList<>(keysOptional.get());
                    List<AttributeKvEntry> data = ctx.loadAttributes(deviceId, DataConstants.CLIENT_SCOPE, keys);
                    List<TsKvEntry> attributesData = data.stream().map(d -> new BasicTsKvEntry(d.getLastUpdateTs(), d)).collect(Collectors.toList());
                    sendWsMsg(ctx, sessionRef, new SubscriptionUpdate(cmd.getCmdId(), attributesData));

                    Map<String, Long> subState = new HashMap<>(keys.size());
                    keys.forEach(key -> subState.put(key, 0L));
                    attributesData.forEach(v -> subState.put(v.getKey(), v.getTs()));

                    sub = new SubscriptionState(sessionId, cmd.getCmdId(), deviceId, SubscriptionType.ATTRIBUTES, false, subState);
                } else {
                    List<AttributeKvEntry> data = ctx.loadAttributes(deviceId, DataConstants.CLIENT_SCOPE);
                    List<TsKvEntry> attributesData = data.stream().map(d -> new BasicTsKvEntry(d.getLastUpdateTs(), d)).collect(Collectors.toList());
                    sendWsMsg(ctx, sessionRef, new SubscriptionUpdate(cmd.getCmdId(), attributesData));

                    Map<String, Long> subState = new HashMap<>(attributesData.size());
                    attributesData.forEach(v -> subState.put(v.getKey(), v.getTs()));

                    sub = new SubscriptionState(sessionId, cmd.getCmdId(), deviceId, SubscriptionType.ATTRIBUTES, true, subState);
                }
                subscriptionManager.addLocalWsSubscription(ctx, sessionId, deviceId, sub);
            }
        }
    }

    private void handleWsTimeseriesSubscriptionCmd(PluginContext ctx, PluginWebsocketSessionRef sessionRef, TimeseriesSubscriptionCmd cmd) {
        String sessionId = sessionRef.getSessionId();
        log.debug("[{}] Processing: {}", sessionId, cmd);

        if (validateSessionMetadata(ctx, sessionRef, cmd, sessionId)) {
            if (cmd.isUnsubscribe()) {
                unsubscribe(ctx, cmd, sessionId);
            } else if (validateSubscriptionCmd(ctx, sessionRef, cmd)) {
                DeviceId deviceId = DeviceId.fromString(cmd.getDeviceId());
                Optional<Set<String>> keysOptional = getKeys(cmd);

                if (keysOptional.isPresent()) {
                    long startTs;
                    if (cmd.getTimeWindow() > 0) {
                        List<TsKvEntry> data = new ArrayList<>();
                        List<String> keys = new ArrayList<>(getKeys(cmd).orElse(Collections.emptySet()));
                        log.debug("[{}] fetching timeseries data for last {} ms for keys: ({}) for device : {}", sessionId, cmd.getTimeWindow(), cmd.getKeys(), cmd.getDeviceId());
                        long endTs = System.currentTimeMillis();
                        startTs = endTs - cmd.getTimeWindow();
                        for (String key : keys) {
                            TsKvQuery query = new BaseTsKvQuery(key, startTs, endTs);
                            data.addAll(ctx.loadTimeseries(deviceId, query));
                        }
                        sendWsMsg(ctx, sessionRef, new SubscriptionUpdate(cmd.getCmdId(), data));

                        Map<String, Long> subState = new HashMap<>(keys.size());
                        keys.forEach(key -> subState.put(key, startTs));
                        data.forEach(v -> subState.put(v.getKey(), v.getTs()));
                        SubscriptionState sub = new SubscriptionState(sessionId, cmd.getCmdId(), deviceId, SubscriptionType.TIMESERIES, false, subState);
                        subscriptionManager.addLocalWsSubscription(ctx, sessionId, deviceId, sub);
                    } else {
                        List<String> keys = new ArrayList<>(getKeys(cmd).orElse(Collections.emptySet()));
                        startTs = System.currentTimeMillis();
                        log.debug("[{}] fetching latest timeseries data for keys: ({}) for device : {}", sessionId, cmd.getKeys(), cmd.getDeviceId());
                        ctx.loadLatestTimeseries(deviceId, keys, new PluginCallback<List<TsKvEntry>>() {
                            @Override
                            public void onSuccess(PluginContext ctx, List<TsKvEntry> data) {
                                sendWsMsg(ctx, sessionRef, new SubscriptionUpdate(cmd.getCmdId(), data));

                                Map<String, Long> subState = new HashMap<>(keys.size());
                                keys.forEach(key -> subState.put(key, startTs));
                                data.forEach(v -> subState.put(v.getKey(), v.getTs()));
                                SubscriptionState sub = new SubscriptionState(sessionId, cmd.getCmdId(), deviceId, SubscriptionType.TIMESERIES, false, subState);
                                subscriptionManager.addLocalWsSubscription(ctx, sessionId, deviceId, sub);
                            }

                            @Override
                            public void onFailure(PluginContext ctx, Exception e) {
                                SubscriptionUpdate update = new SubscriptionUpdate(cmd.getCmdId(), SubscriptionErrorCode.INTERNAL_ERROR,
                                        "Failed to fetch data!");
                                sendWsMsg(ctx, sessionRef, update);
                            }
                        });
                    }
                } else {
                    ctx.loadLatestTimeseries(deviceId, new PluginCallback<List<TsKvEntry>>() {
                        @Override
                        public void onSuccess(PluginContext ctx, List<TsKvEntry> data) {
                            sendWsMsg(ctx, sessionRef, new SubscriptionUpdate(cmd.getCmdId(), data));
                            Map<String, Long> subState = new HashMap<>(data.size());
                            data.forEach(v -> subState.put(v.getKey(), v.getTs()));
                            SubscriptionState sub = new SubscriptionState(sessionId, cmd.getCmdId(), deviceId, SubscriptionType.TIMESERIES, true, subState);
                            subscriptionManager.addLocalWsSubscription(ctx, sessionId, deviceId, sub);
                        }

                        @Override
                        public void onFailure(PluginContext ctx, Exception e) {
                            SubscriptionUpdate update = new SubscriptionUpdate(cmd.getCmdId(), SubscriptionErrorCode.INTERNAL_ERROR,
                                    "Failed to fetch data!");
                            sendWsMsg(ctx, sessionRef, update);
                        }
                    });
                }
            }
        }
    }

    private void handleWsHistoryCmd(PluginContext ctx, PluginWebsocketSessionRef sessionRef, GetHistoryCmd cmd) {
        String sessionId = sessionRef.getSessionId();
        WsSessionMetaData sessionMD = wsSessionsMap.get(sessionId);
        if (sessionMD == null) {
            log.warn("[{}] Session meta data not found. ", sessionId);
            SubscriptionUpdate update = new SubscriptionUpdate(cmd.getCmdId(), SubscriptionErrorCode.INTERNAL_ERROR,
                    "Session meta-data not found!");
            sendWsMsg(ctx, sessionRef, update);
            return;
        }
        if (cmd.getDeviceId() == null || cmd.getDeviceId().isEmpty()) {
            SubscriptionUpdate update = new SubscriptionUpdate(cmd.getCmdId(), SubscriptionErrorCode.BAD_REQUEST,
                    "Device id is empty!");
            sendWsMsg(ctx, sessionRef, update);
            return;
        }
        if (cmd.getKeys() == null || cmd.getKeys().isEmpty()) {
            SubscriptionUpdate update = new SubscriptionUpdate(cmd.getCmdId(), SubscriptionErrorCode.BAD_REQUEST,
                    "Keys are empty!");
            sendWsMsg(ctx, sessionRef, update);
            return;
        }
        DeviceId deviceId = DeviceId.fromString(cmd.getDeviceId());
        if (!ctx.checkAccess(deviceId)) {
            SubscriptionUpdate update = new SubscriptionUpdate(cmd.getCmdId(), SubscriptionErrorCode.UNAUTHORIZED,
                    SubscriptionErrorCode.UNAUTHORIZED.getDefaultMsg());
            sendWsMsg(ctx, sessionRef, update);
            return;
        }
        List<String> keys = new ArrayList<>(getKeys(cmd).orElse(Collections.emptySet()));
        List<TsKvEntry> data = new ArrayList<>();
        for (String key : keys) {
            TsKvQuery query = new BaseTsKvQuery(key, cmd.getStartTs(), cmd.getEndTs());
            data.addAll(ctx.loadTimeseries(deviceId, query));
        }
        sendWsMsg(ctx, sessionRef, new SubscriptionUpdate(cmd.getCmdId(), data));
    }

    private boolean validateSessionMetadata(PluginContext ctx, PluginWebsocketSessionRef sessionRef, SubscriptionCmd cmd, String sessionId) {
        WsSessionMetaData sessionMD = wsSessionsMap.get(sessionId);
        if (sessionMD == null) {
            log.warn("[{}] Session meta data not found. ", sessionId);
            SubscriptionUpdate update = new SubscriptionUpdate(cmd.getCmdId(), SubscriptionErrorCode.INTERNAL_ERROR,
                    "Session meta-data not found!");
            sendWsMsg(ctx, sessionRef, update);
            return false;
        } else {
            return true;
        }
    }

    private void unsubscribe(PluginContext ctx, SubscriptionCmd cmd, String sessionId) {
        if (cmd.getDeviceId() == null || cmd.getDeviceId().isEmpty()) {
            cleanupWebSocketSession(ctx, sessionId);
        } else {
            subscriptionManager.removeSubscription(ctx, sessionId, cmd.getCmdId());
        }
    }

    private boolean validateSubscriptionCmd(PluginContext ctx, PluginWebsocketSessionRef sessionRef, SubscriptionCmd cmd) {
        if (cmd.getDeviceId() == null || cmd.getDeviceId().isEmpty()) {
            SubscriptionUpdate update = new SubscriptionUpdate(cmd.getCmdId(), SubscriptionErrorCode.BAD_REQUEST,
                    "Device id is empty!");
            sendWsMsg(ctx, sessionRef, update);
            return false;
        }
        DeviceId deviceId = DeviceId.fromString(cmd.getDeviceId());
        if (!ctx.checkAccess(deviceId)) {
            SubscriptionUpdate update = new SubscriptionUpdate(cmd.getCmdId(), SubscriptionErrorCode.UNAUTHORIZED,
                    SubscriptionErrorCode.UNAUTHORIZED.getDefaultMsg());
            sendWsMsg(ctx, sessionRef, update);
            return false;
        }
        return true;
    }

    private void sendWsMsg(PluginContext ctx, PluginWebsocketSessionRef sessionRef, SubscriptionUpdate update) {
        TextPluginWebSocketMsg reply;
        try {
            reply = new TextPluginWebSocketMsg(sessionRef, jsonMapper.writeValueAsString(update));
            ctx.send(reply);
        } catch (JsonProcessingException e) {
            log.warn("[{}] Failed to encode reply: {}", sessionRef.getSessionId(), update, e);
        } catch (IOException e) {
            log.warn("[{}] Failed to send reply: {}", sessionRef.getSessionId(), update, e);
        }
    }

    public static Optional<Set<String>> getKeys(TelemetryPluginCmd cmd) {
        if (!StringUtils.isEmpty(cmd.getKeys())) {
            Set<String> keys = new HashSet<>();
            for (String key : cmd.getKeys().split(",")) {
                keys.add(key);
            }
            return Optional.of(keys);
        } else {
            return Optional.empty();
        }
    }

    public void sendWsMsg(PluginContext ctx, String sessionId, SubscriptionUpdate update) {
        WsSessionMetaData md = wsSessionsMap.get(sessionId);
        if (md != null) {
            sendWsMsg(ctx, md.getSessionRef(), update);
        }
    }
}
