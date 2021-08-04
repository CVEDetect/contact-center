package org.zhongweixian.cc.command;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.cti.cc.entity.CallDetail;
import org.cti.cc.enums.CauseEnums;
import org.cti.cc.enums.NextType;
import org.cti.cc.po.*;
import org.cti.cc.strategy.AgentStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.zhongweixian.cc.cache.CacheService;
import org.zhongweixian.cc.command.base.BaseHandler;
import org.zhongweixian.cc.fs.FsListen;
import org.zhongweixian.cc.service.AgentService;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.*;

/**
 * Create by caoliang on 2020/8/23
 * <p>
 * 进技能组
 */
@Component
public class GroupHandler extends BaseHandler {

    /**
     * 排队电话
     */
    private Map<Long, PriorityQueue<CallQueue>> callInfoMap = new ConcurrentHashMap<>();

    /**
     * 空闲坐席
     */
    private Map<Long, PriorityQueue<AgentQueue>> agentInfoMap = new ConcurrentHashMap<>();


    @Autowired
    private CacheService cacheService;

    @Autowired
    private FsListen fsListen;

    @Autowired
    private AgentService agentService;

    @Autowired
    private OverFlowHandler overFlowHandler;

    @Autowired
    private TransferAgentHandler transferAgentHandler;

    /**
     * 转接坐席线程
     */
    private ThreadPoolExecutor wakeupCallService = new ThreadPoolExecutor(4, 4, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactoryBuilder().setNameFormat("wakeup-call-pool-%d").build());


    /**
     * 检测电话排队定时线程
     */
    private ScheduledExecutorService checkCallService = new ScheduledThreadPoolExecutor(1, new ThreadFactoryBuilder().setNameFormat("check-call-pool-%d").build());


    /**
     * 电话进入技能组,呼入电话有可能多次经过这
     *
     * @param callInfo
     */
    public void hander(String deviceId, CallInfo callInfo, GroupInfo groupInfo) {
        if (deviceId == null || groupInfo == null) {
            return;
        }
        logger.info("callId:{} on group:{}", callInfo.getCallId(), groupInfo.getName());
        CallDetail joinGroup = null;
        Long now = Instant.now().toEpochMilli();
        if (callInfo.getFristQueueTime() == null) {
            callInfo.setFristQueueTime(now);
        }
        //电话经过技能组
        joinGroup = new CallDetail();
        joinGroup.setCallId(callInfo.getCallId());
        joinGroup.setCts(now);
        joinGroup.setStartTime(now);
        joinGroup.setDetailIndex(callInfo.getCallDetails().size() + 1);
        joinGroup.setTransferType(3);
        joinGroup.setTransferId(callInfo.getGroupId());
        callInfo.getCallDetails().add(joinGroup);
        callInfo.setQueueStartTime(now);

        Long groupId = callInfo.getGroupId();
        AgentInfo agentInfo = getAgentQueue(groupId);
        if (agentInfo != null) {
            logger.info("callId:{} get free agent:{} on group:{}", callInfo.getCallId(), agentInfo.getAgentKey(), groupId);
            //呼叫坐席
            Long end = Instant.now().toEpochMilli();
            callInfo.setQueueEndTime(end);
            agentNotReady(agentInfo);
            if (joinGroup == null) {
                joinGroup = callInfo.getCallDetails().get(callInfo.getCallDetails().size() - 1);
            }
            joinGroup.setEndTime(end);
            callAgent(agentInfo, callInfo, deviceId);
            return;
        }
        logger.info("callId:{} join group:{} agent busy", callInfo.getCallId(), groupInfo.getName());

        //排队溢出策略
        GroupOverflowPo groupOverFlow = getEffectiveOverflow(groupInfo);
        if (groupOverFlow == null) {
            logger.warn("groupName:{} of groupOverflow is null, callId:{}", groupInfo.getName(), callInfo.getCallId());
            return;
        }


        /**
         * 1:排队,2:溢出,3:挂机
         */
        switch (groupOverFlow.getHandleType()) {
            case 1:
                logger.info("group:{} handleType is lineUp, queueTimeout:{}, busyType:{}, busyTimeoutType:{}, overflowType:{}, overflowValue:{}, callId:{}", groupInfo.getName(), groupOverFlow.getQueueTimeout(), groupOverFlow.getBusyType(), groupOverFlow.getBusyTimeoutType(), groupOverFlow.getOverflowType(), groupOverFlow.getOverflowValue(), callInfo.getCallId());
                PriorityQueue<CallQueue> callQueues = callInfoMap.get(groupId);
                if (callQueues == null) {
                    callQueues = new PriorityQueue<CallQueue>();
                }
                Long queueLevel = groupOverFlow.getLineupStrategy().calculateLevel(callInfo);
                callInfo.setQueueLevel(queueLevel);
                callQueues.add(new CallQueue(callInfo.getQueueLevel(), callInfo.getCallId(), deviceId, callInfo.getQueueStartTime() / 1000, groupId, groupOverFlow));
                callInfoMap.put(callInfo.getGroupId(), callQueues);

                /**
                 * 放音
                 */
                fsListen.playback(callInfo.getMedia(), deviceId, "/app/clpms/sounds/queue.wav");
                DeviceInfo deviceInfo = callInfo.getDeviceInfoMap().get(deviceId);
                deviceInfo.setNextCommand(new NextCommand(NextType.NEXT_QUEUE_PLAY));
                break;

            case 2:
                overFlowHandler.overflow(callInfo, deviceId, groupId);
                break;

            case 3:
                logger.info("group:{} handleType is hangup, callId:{}", groupInfo.getName(), callInfo.getCallId());
                //技能组策略挂机
                callInfo.setHangupDir(3);
                callInfo.setHangupCause(CauseEnums.OVERFLOW_TIMEOUT.name());
                hangupCall(callInfo.getMedia(), callInfo.getCallId(), deviceId);
                break;
            default:
                break;
        }
    }

    /**
     * 转坐席
     *
     * @param agentInfo
     * @param callInfo
     * @param thisDeviceId
     */
    private void callAgent(AgentInfo agentInfo, CallInfo callInfo, String thisDeviceId) {
        if (!CollectionUtils.isEmpty(callInfo.getCallDetails())) {
            CallDetail callDetail = callInfo.getCallDetails().get(callInfo.getCallDetails().size() - 1);
            if (callDetail != null) {
                callDetail.setEndTime(Instant.now().toEpochMilli());
            }
        }
        transferAgentHandler.hanlder(callInfo, agentInfo, thisDeviceId);
    }

    /**
     * 获取空闲坐席
     *
     * @param groupId
     * @return
     */
    private AgentInfo getAgentQueue(Long groupId) {
        if (CollectionUtils.isEmpty(agentInfoMap.get(groupId))) {
            return null;
        }
        AgentQueue agentQueue = agentInfoMap.get(groupId).poll();
        if (agentQueue == null) {
            return null;
        }
        AgentInfo agentInfo = cacheService.getAgentInfo(agentQueue.getAgentKey());
        if (agentInfo == null) {
            return getAgentQueue(groupId);
        }
        return agentInfo;
    }


    /**
     * 坐席空闲
     *
     * @param agentInfo
     */
    public void agentFree(AgentInfo agentInfo) {
        agentInfo.getGroupIds().forEach(groupId -> {
            PriorityQueue<AgentQueue> agentQueues = agentInfoMap.get(groupId);
            if (agentQueues == null) {
                agentQueues = new PriorityQueue<AgentQueue>();
            }
            logger.info("agent:{} ready for group:{}", agentInfo.getAgentKey(), groupId);
            //根据空闲策略
            GroupInfo groupInfo = cacheService.getGroupInfo(groupId);
            //坐席空闲策略接口
            AgentStrategy agentStrategy = groupInfo.getGroupAgentStrategyPo().getAgentStrategy();
            Long priority = agentStrategy.calculateLevel(agentInfo);
            agentQueues.offer(new AgentQueue(priority, agentInfo.getAgentKey()));
            agentInfoMap.put(groupId, agentQueues);

        });
    }


    /**
     * 坐席忙碌
     *
     * @param agentInfo
     */
    public void agentNotReady(AgentInfo agentInfo) {
        agentInfo.getGroupIds().forEach(groupId -> {
            PriorityQueue<AgentQueue> agentQueues = agentInfoMap.get(groupId);
            if (agentQueues == null) {
                return;
            }
            logger.info("agent:{} not ready for group:{}", agentInfo.getAgentKey(), groupId);
            agentQueues.remove(new AgentQueue(1L, agentInfo.getAgentKey()));
            agentInfoMap.put(groupId, agentQueues);
        });
    }

    /**
     * 删除电话
     *
     * @param groupId
     * @param callId
     */
    public void removeCall(Long groupId, Long callId) {
        if (groupId == null || callId == null) {
            return;
        }
        PriorityQueue<CallQueue> callQueues = callInfoMap.get(groupId);
        if (callQueues == null) {
            return;
        }
        callQueues.remove(callId);
    }

    /**
     * 获取有效的排队策略
     *
     * @param groupInfo
     * @return
     */
    public GroupOverflowPo getEffectiveOverflow(GroupInfo groupInfo) {
        if (CollectionUtils.isEmpty(groupInfo.getGroupOverflows())) {
            return null;
        }
        for (GroupOverflowPo groupOverflowPo : groupInfo.getGroupOverflows()) {
            return groupOverflowPo;
        }
        return null;
    }

    /**
     * 超时出队列
     *
     * @param callQueue
     */
    private void queueTimeout(CallQueue callQueue) {
        CallInfo callInfo = cacheService.getCallInfo(callQueue.getCallId());
        DeviceInfo deviceInfo = callInfo.getDeviceInfoMap().get(callQueue.deviceId);
        GroupOverflowPo groupOverflowPo = callQueue.getGroupOverflowPo();
        logger.info("callId:{} queueTimeout, busyTimeoutType:{}", callQueue.getCallId(), groupOverflowPo.getBusyTimeoutType());
        switch (groupOverflowPo.getBusyTimeoutType()) {
            case 1:
                //排队超时走溢出策略,1:group,2:ivr,3:vdn
                switch (groupOverflowPo.getOverflowType()) {
                    case 1:
                        deviceInfo.setNextCommand(new NextCommand(NextType.NEXT_QUEUE_OVERFLOW_GROUP, groupOverflowPo.getOverflowValue().toString()));
                        break;
                    case 2:
                        deviceInfo.setNextCommand(new NextCommand(NextType.NEXT_QUEUE_OVERFLOW_IVR, groupOverflowPo.getOverflowValue().toString()));
                        break;
                    case 3:
                        deviceInfo.setNextCommand(new NextCommand(NextType.NEXT_QUEUE_OVERFLOW_VDN, groupOverflowPo.getOverflowValue().toString()));
                        break;
                }
                break;
            case 2:
                //排队超时挂机
                callInfo.setHangupDir(3);
                callInfo.setHangupCause(CauseEnums.QUEUE_TIMEOUT.name());
                deviceInfo.setNextCommand(new NextCommand(NextType.NEXT_HANGUP, groupOverflowPo.getOverflowValue().toString()));
                break;

            default:
                logger.warn("============:{}", callQueue);
                break;
        }
        fsListen.playbreak(callInfo.getMedia(), callQueue.getDeviceId());
        doNextCommand(callInfo, deviceInfo);
    }

    /**
     * 进入到队列的电话，需要定时找空闲坐席
     */
    public void start() {
        checkCallService.scheduleAtFixedRate(() -> {
            Long now = Instant.now().getEpochSecond();
            callInfoMap.forEach((k, v) -> {
                if (!v.isEmpty()) {
                    Iterator<CallQueue> iterator = v.iterator();
                    while (iterator.hasNext()) {
                        CallQueue callQueue = iterator.next();
                        if (now - callQueue.getStartTime() > callQueue.getGroupOverflowPo().getQueueTimeout().longValue()) {
                            queueTimeout(callQueue);
                            iterator.remove();
                            continue;
                        }
                        //查找空闲坐席
                        AgentInfo agentInfo = this.getAgentQueue(k);
                        if (agentInfo != null) {
                            iterator.remove();
                            //先停止放音
                            CallInfo callInfo = cacheService.getCallInfo(callQueue.getCallId());
                            if (callInfo == null) {
                                continue;
                            }
                            DeviceInfo deviceInfo = callInfo.getDeviceInfoMap().get(callQueue.getDeviceId());
                            if (deviceInfo != null) {
                                deviceInfo.setNextCommand(null);
                            }
                            fsListen.playbreak(callInfo.getMedia(), callQueue.getDeviceId());
                            this.callAgent(agentInfo, callInfo, callQueue.getDeviceId());
                        }
                    }
                }
            });
        }, 5000, 200, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        checkCallService.shutdown();
        wakeupCallService.shutdown();
    }


    class AgentQueue implements Comparable<AgentQueue> {
        private Long priority;

        private String agentKey;

        public AgentQueue(Long priority, String agentKey) {
            this.priority = priority;
            this.agentKey = agentKey;
        }

        public Long getPriority() {
            return priority;
        }

        public void setPriority(Long priority) {
            this.priority = priority;
        }

        public String getAgentKey() {
            return agentKey;
        }

        public void setAgentKey(String agentKey) {
            this.agentKey = agentKey;
        }

        @Override
        public int compareTo(AgentQueue o) {
            return o.priority.compareTo(this.priority);
        }

        @Override
        public boolean equals(Object obj) {
            AgentQueue agentQueue = (AgentQueue) obj;
            return this.agentKey.equals(agentQueue.getAgentKey());
        }
    }


    class CallQueue implements Comparable<CallQueue> {
        private Long priority;

        private Long callId;

        private Long startTime;

        private Long groupId;

        private GroupOverflowPo groupOverflowPo;

        private String deviceId;

        public CallQueue(Long priority, Long callId, String deviceId, Long startTime, Long groupId, GroupOverflowPo groupOverflowPo) {
            this.priority = priority;
            this.callId = callId;
            this.startTime = startTime;
            this.deviceId = deviceId;
            this.groupId = groupId;
            this.groupOverflowPo = groupOverflowPo;
        }

        public Long getPriority() {
            return priority;
        }

        public void setPriority(Long priority) {
            this.priority = priority;
        }

        public Long getCallId() {
            return callId;
        }

        public void setCallId(Long callId) {
            this.callId = callId;
        }

        public Long getStartTime() {
            return startTime;
        }

        public void setStartTime(Long startTime) {
            this.startTime = startTime;
        }

        public Long getGroupId() {
            return groupId;
        }

        public void setGroupId(Long groupId) {
            this.groupId = groupId;
        }

        public GroupOverflowPo getGroupOverflowPo() {
            return groupOverflowPo;
        }

        public void setGroupOverflowPo(GroupOverflowPo groupOverflowPo) {
            this.groupOverflowPo = groupOverflowPo;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        @Override
        public int compareTo(CallQueue o) {
            return o.priority.compareTo(this.priority);
        }

        @Override
        public boolean equals(Object obj) {
            return this.callId.equals(obj);
        }
    }
}
