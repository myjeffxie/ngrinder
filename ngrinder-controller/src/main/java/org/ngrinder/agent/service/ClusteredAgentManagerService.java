/*
 * Copyright (C) 2012 - 2012 NHN Corporation
 * All rights reserved.
 *
 * This file is part of The nGrinder software distribution. Refer to
 * the file LICENSE which is part of The nGrinder distribution for
 * licensing details. The nGrinder distribution is available on the
 * Internet at http://nhnopensource.org/ngrinder
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngrinder.agent.service;

import static org.ngrinder.agent.repository.AgentManagerSpecification.startWithRegion;
import static org.ngrinder.common.util.TypeConvertUtil.convert;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import net.grinder.common.processidentity.AgentIdentity;
import net.grinder.engine.controller.AgentControllerIdentityImplementation;
import net.grinder.message.console.AgentControllerState;
import net.grinder.util.thread.InterruptibleRunnable;
import net.sf.ehcache.Ehcache;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.mutable.MutableInt;
import org.ngrinder.agent.model.AgentInfo;
import org.ngrinder.agent.model.ClustedAgentRequest;
import org.ngrinder.agent.model.ClustedAgentRequest.RequestType;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.logger.CoreLogger;
import org.ngrinder.infra.schedule.ScheduledTask;
import org.ngrinder.model.User;
import org.ngrinder.monitor.controller.model.SystemDataModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

/**
 * Cluster enabled version of {@link AgentManagerService}.
 * 
 * @author JunHo Yoon
 * @since 3.1
 */
public class ClusteredAgentManagerService extends AgentManagerService {
	private static final Logger LOGGER = LoggerFactory.getLogger(ClusteredAgentManagerService.class);

	@Autowired
	private CacheManager cacheManager;

	private Cache agentRequestCache;

	private Cache agentMonitorCache;

	private Cache agentMonioringTargetsCache;

	@Autowired
	private ScheduledTask scheduledTask;

	/**
	 * Initialize.
	 */
	@PostConstruct
	public void init() {
		agentMonioringTargetsCache = getCacheManager().getCache("agent_monitoring_targets");
		if (getConfig().isCluster()) {
			agentRequestCache = getCacheManager().getCache("agent_request");
			agentMonitorCache = getCacheManager().getCache("agent_monitoring");
			scheduledTask.addScheduledTaskEvery3Sec(new InterruptibleRunnable() {
				@SuppressWarnings({ "unchecked", "rawtypes" })
				@Override
				public void interruptibleRun() {
					List keysWithExpiryCheck = ((Ehcache) agentRequestCache.getNativeCache()).getKeysWithExpiryCheck();
					String region = getConfig().getRegion() + "_";
					for (String each : (List<String>) keysWithExpiryCheck) {
						try {
							if (each.startsWith(region) && agentRequestCache.get(each) != null) {
								ClustedAgentRequest agentRequest = (ClustedAgentRequest) (agentRequestCache.get(each)
												.get());
								AgentControllerIdentityImplementation agentIdentity = getLocalAgentIdentityByIpAndName(
												agentRequest.getAgentIp(), agentRequest.getAgentName());
								if (agentIdentity != null) {
									agentRequest.getRequestType().process(agentRequest.getAgentId(),
													agentRequest.getAgentIp(), getAgentManager(),
													ClusteredAgentManagerService.this, agentIdentity);
								}
							}

						} catch (Exception e) {
							CoreLogger.LOGGER.error(e.getMessage(), e);
						}
						agentRequestCache.evict(each);
					}
				}
			});
		}
	}

	/**
	 * Run a scheduled task to check the agent status.
	 * 
	 * This method has some
	 * 
	 * @since 3.1
	 */
	@Scheduled(fixedDelay = 5000)
	@Transactional
	public void checkAgentStatus() {
		List<AgentInfo> changeAgents = Lists.newArrayList();

		Set<AgentIdentity> allAttachedAgents = getAgentManager().getAllAttachedAgents();
		Map<String, AgentControllerIdentityImplementation> attachedAgentMap = Maps.newHashMap();
		for (AgentIdentity agentIdentity : allAttachedAgents) {
			AgentControllerIdentityImplementation agentControllerIdentity = convert(agentIdentity);
			attachedAgentMap.put(createAgentKey(agentControllerIdentity), agentControllerIdentity);
		}

		String region = getConfig().getRegion();
		// If region is not specified retrieved all
		List<AgentInfo> agentsInDB = getAgentRepository().findAll(startWithRegion(region));

		Multimap<String, AgentInfo> agentInDBMap = ArrayListMultimap.create();
		// step1. check all agents in DB, whether they are attached to
		// controller.
		for (AgentInfo each : agentsInDB) {
			agentInDBMap.put(createAgentKey(each), each);
		}

		List<AgentInfo> agentsToBeDeleted = Lists.newArrayList();
		for (String entryKey : agentInDBMap.keySet()) {
			Collection<AgentInfo> collection = agentInDBMap.get(entryKey);
			int count = 0;
			AgentInfo interestingAgentInfo = null;
			for (AgentInfo each : collection) {
				// Just select one element and delete others.
				if (count++ == 0) {
					interestingAgentInfo = each;
				} else {
					agentsToBeDeleted.add(each);
				}
			}
			if (interestingAgentInfo == null) {
				continue;
			}

			AgentControllerIdentityImplementation agentIdentity = attachedAgentMap.remove(entryKey);
			if (agentIdentity == null) {
				// this agent is not attached to controller
				interestingAgentInfo.setStatus(AgentControllerState.INACTIVE);
			} else {
				interestingAgentInfo.setStatus(getAgentManager().getAgentState(agentIdentity));
				interestingAgentInfo.setRegion(agentIdentity.getRegion());
				interestingAgentInfo.setPort(getAgentManager().getAgentConnectingPort(agentIdentity));
			}
			changeAgents.add(interestingAgentInfo);
		}

		// step2. check all attached agents, whether they are new, and not saved
		// in DB.
		for (AgentControllerIdentityImplementation agentIdentity : attachedAgentMap.values()) {
			if (StringUtils.equals(region, agentIdentity.getRegion())) {
				changeAgents.add(fillUpAgentInfo(new AgentInfo(), agentIdentity));
			} else {
				// If
				AgentInfo findByIpAndHostName = getAgentRepository().findByIpAndHostName(agentIdentity.getIp(),
								agentIdentity.getName());
				if (findByIpAndHostName != null) {
					findByIpAndHostName.setStatus(AgentControllerState.UNKNOWN);
					changeAgents.add(findByIpAndHostName);
				} else {
					AgentInfo fillUpAgentInfo = fillUpAgentInfo(new AgentInfo(), agentIdentity);
					fillUpAgentInfo.setApproved(false);
					fillUpAgentInfo.setStatus(AgentControllerState.UNKNOWN);
					changeAgents.add(findByIpAndHostName);
				}
			}
		}

		// step3. update into DB
		getAgentRepository().save(changeAgents);
		getAgentRepository().delete(agentsToBeDeleted);
	}

	/**
	 * collect agent system data every 1 sec.
	 * 
	 */
	@Scheduled(fixedDelay = 1000)
	public void collectAgentSystemData() {
		Ehcache nativeCache = (Ehcache) agentMonioringTargetsCache.getNativeCache();
		List<String> keysWithExpiryCheck = convert(nativeCache.getKeysWithExpiryCheck());
		for (String each : keysWithExpiryCheck) {
			ValueWrapper value = agentMonioringTargetsCache.get(each);
			if (value != null && value.get() != null) {
				agentMonitorCache.put(each, getAgentManager().getSystemDataModel((AgentIdentity) value.get()));
			}
		}
	}

	/**
	 * get the available agent count map in all regions of the user, including the free agents and
	 * user specified agents.
	 * 
	 * @param regions
	 *            current region list
	 * @param user
	 *            current user
	 * @return user available agent count map
	 */
	public Map<String, MutableInt> getUserAvailableAgentCountMap(List<String> regions, User user) {
		Map<String, MutableInt> availableShareAgents = new HashMap<String, MutableInt>(regions.size());
		Map<String, MutableInt> availableUserOwnAgent = new HashMap<String, MutableInt>(regions.size());
		for (String region : regions) {
			availableShareAgents.put(region, new MutableInt(0));
			availableUserOwnAgent.put(region, new MutableInt(0));
		}
		String myAgentSuffix = "_owned_" + user.getUserId();

		for (AgentInfo agentInfo : getAllActiveAgentInfoFromDB()) {
			// Skip the all agents which doesn't approved, is inactive or
			// doesn't have region
			// prefix.
			if (!agentInfo.isApproved()) {
				continue;
			}

			String fullRegion = agentInfo.getRegion();
			String region = extractRegionFromAgentRegion(fullRegion);
			if (StringUtils.isBlank(region)) {
				continue;
			}
			// It's my own agent
			if (fullRegion.endsWith(myAgentSuffix)) {
				incrementAgentCount(availableUserOwnAgent, region, user.getUserId());
			} else if (fullRegion.contains("_owned_")) {
				// If it's the others agent.. skip..
				continue;
			} else {
				incrementAgentCount(availableShareAgents, region, user.getUserId());
			}
		}

		int maxAgentSizePerConsole = getMaxAgentSizePerConsole();

		for (String region : regions) {
			MutableInt mutableInt = availableShareAgents.get(region);
			int shareAgentCount = mutableInt.intValue();
			mutableInt.setValue(Math.min(shareAgentCount, maxAgentSizePerConsole));
			mutableInt.add(availableUserOwnAgent.get(region));
		}
		return availableShareAgents;
	}

	String extractRegionFromAgentRegion(String agentRegion) {
		if (agentRegion.contains("_owned_")) {
			return agentRegion.substring(0, agentRegion.lastIndexOf("_owned_"));
		} else if (agentRegion.contains("owned_")) {
			return Config.NON_REGION;
		}
		return agentRegion;
	}

	private void incrementAgentCount(Map<String, MutableInt> agentMap, String region, String userId) {
		if (!agentMap.containsKey(region)) {
			LOGGER.warn("Region :{} not exist in cluster nor owned by user:{}.", region, userId);
		} else {
			agentMap.get(region).increment();
		}
	}

	/**
	 * /** Get all agents attached of this region from DB.
	 * 
	 * This method is cluster aware. If it's cluster mode it return all agents attached in this
	 * region.
	 * 
	 * @return agent list
	 */
	@Override
	public List<AgentInfo> getLocalAgentListFromDB() {
		return getAgentRepository().findAll(startWithRegion(getConfig().getRegion()));
	}

	/**
	 * Stop agent. In cluster mode, it queues the agent stop request to agentRequestCache.
	 * 
	 * @param id
	 *            agent id in db
	 * 
	 */
	@Override
	public void stopAgent(Long id) {
		AgentInfo agent = getAgent(id, false);
		if (agent == null) {
			return;
		}
		agentRequestCache.put(extractRegionFromAgentRegion(agent.getRegion()) + "_" + agent.getId() + "_stop_agent",
						new ClustedAgentRequest(agent.getId(), agent.getIp(), agent.getName(), RequestType.STOP_AGENT));
	}

	/**
	 * Add the agent system data model share request on cache.
	 * 
	 * @param id
	 *            agent id in db.
	 */
	@Override
	public void requestShareAgentSystemDataModel(Long id) {
		AgentInfo agent = getAgent(id, false);
		agentRequestCache.put(extractRegionFromAgentRegion(agent.getRegion()) + "_" + agent.getId() + "_monitoring",
						new ClustedAgentRequest(agent.getId(), agent.getIp(), agent.getName(),
										RequestType.SHARE_AGENT_SYSTEM_DATA_MODEL));
	}

	/**
	 * Get agent system data model for the given ip. This method is cluster aware.
	 * 
	 * @param ip
	 *            agent ip.
	 * @return {@link SystemDataModel} instance.
	 */
	@Override
	public SystemDataModel getAgentSystemDataModel(String ip, String name) {
		ValueWrapper valueWrapper = agentMonitorCache.get(ip);
		return valueWrapper == null ? new SystemDataModel() : (SystemDataModel) valueWrapper.get();
	}

	/**
	 * Register agent monitoring target. This method should be called in the controller in which the
	 * given agent exists.
	 * 
	 * @param id
	 *            agent id
	 * @param ip
	 *            agent ip
	 * @param agentIdentity
	 *            agent identity
	 */
	public void addAgentMonitoringTarget(Long id, String ip, AgentControllerIdentityImplementation agentIdentity) {
		agentMonioringTargetsCache.put(createAgentKey(agentIdentity), agentIdentity);
	}

	CacheManager getCacheManager() {
		return cacheManager;
	}

	void setCacheManager(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

}