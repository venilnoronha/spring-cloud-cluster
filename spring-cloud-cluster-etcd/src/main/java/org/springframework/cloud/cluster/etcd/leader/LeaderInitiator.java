/*
 * Copyright 2014-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.cluster.etcd.leader;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cloud.cluster.leader.Candidate;
import org.springframework.cloud.cluster.leader.Context;
import org.springframework.cloud.cluster.leader.event.LeaderEventPublisher;
import org.springframework.context.Lifecycle;

import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.responses.EtcdException;

/**
 * Bootstrap leadership {@link org.springframework.cloud.cluster.leader.Candidate candidates}
 * with Etcd. Upon construction, {@link #start} must be invoked to
 * register the candidate for leadership election.
 *
 * @author Venil Noronha
 */
public class LeaderInitiator implements Lifecycle, InitializingBean, DisposableBean {

	private final static int TTL = 10;
	private final static int HEART_BEAT_SLEEP = 1000 * (TTL / 2);
	private final static String DEFAULT_NAMESPACE = "spring-cloud";
	
	/**
	 * Etcd client.
	 */
	private final EtcdClient client;

	/**
	 * Candidate for leader election.
	 */
	private final Candidate candidate;

	/**
	 * Etcd namespace.
	 */
	private final String namespace;
	
	/**
	 * Executor service for running leadership daemon.
	 */
	private final ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			Thread thread = new Thread(r, "Etcd-Leadership");
			thread.setDaemon(true);
			return thread;
		}
	});

	/**
	 * Flag that indicates whether the current candidate is
	 * the leader.
	 */
	private volatile boolean isLeader = false;
	
	/**
	 * Future returned by submitting an {@link Initiator} to {@link #executorService}.
	 * This is used to cancel leadership.
	 */
	private volatile Future<Void> future;

	/**
	 * Flag that indicates whether the leadership election for
	 * this {@link #candidate} is running.
	 */
	private volatile boolean running;

	/** Leader event publisher if set */
	private LeaderEventPublisher leaderEventPublisher;
	
	/**
	 * Construct a {@link LeaderInitiator}.
	 *
	 * @param client     Etcd client
	 * @param candidate  leadership election candidate
	 * @param namespace	 Etcd namespace
	 */
	public LeaderInitiator(EtcdClient client, Candidate candidate, String namespace) {
		this.client = client;
		this.candidate = candidate;
		this.namespace = namespace;
	}

	/**
	 * Start the registration of the {@link #candidate} for leader election.
	 */
	@Override
	public synchronized void start() {
		if (!running) {
			running = true;
			future = executorService.submit(new Initiator());
		}
	}

	/**
	 * Stop the registration of the {@link #candidate} for leader election.
	 * If the candidate is currently leader, its leadership will be revoked.
	 */
	@Override
	public synchronized void stop() {
		if (running) {
			running = false;
			future.cancel(true);
		}
	}

	/**
	 * @return true if leadership election for this {@link #candidate} is running
	 */
	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		start();
	}

	@Override
	public void destroy() throws Exception {
		stop();
		executorService.shutdown();
	}
	
	/**
	 * Sets the {@link LeaderEventPublisher}.
	 * 
	 * @param leaderEventPublisher the event publisher
	 */
	public void setLeaderEventPublisher(LeaderEventPublisher leaderEventPublisher) {
		this.leaderEventPublisher = leaderEventPublisher;
	}
	
	/**
	 * Callable that manages the etcd heart beats for leadership election.
	 */
	class Initiator implements Callable<Void> {
		
		private final String baseDirectory;
		private final EtcdContext context;
		
		public Initiator() {
			baseDirectory = (namespace == null ? DEFAULT_NAMESPACE : namespace) + "/" + candidate.getRole();
			context = new EtcdContext();
		}
		
		@Override
		public Void call() throws Exception {
			while (running) {
				try {
					if (isLeader) {
						try {
							client.put(baseDirectory, candidate.getId()).ttl(TTL).prevValue(candidate.getId())
									.send().get();
						}
						catch (EtcdException e) {
							notifyRevoked();
						}
					}
					else {
						try {
							client.put(baseDirectory, candidate.getId()).ttl(TTL).prevExist(false).send().get();
							notifyGranted();
						}
						catch (EtcdException e) {
							// Keep trying
						}
					}
					Thread.sleep(HEART_BEAT_SLEEP);
				}
				catch (InterruptedException e) {
					if (isLeader) {
						client.delete(baseDirectory).prevValue(candidate.getId()).send();
						notifyRevoked();
					}
				}
			}
			return null;
		}

		private void notifyGranted() throws InterruptedException {
			isLeader = true;
			candidate.onGranted(context);
			if (leaderEventPublisher != null) {
				leaderEventPublisher.publishOnGranted(LeaderInitiator.this, context);
			}
		}

		private void notifyRevoked() {
			isLeader = false;
			candidate.onRevoked(context);
			if (leaderEventPublisher != null) {
				leaderEventPublisher.publishOnRevoked(LeaderInitiator.this, context);
			}
		}

	}

	/**
	 * Implementation of leadership context backed by Etcd.
	 */
	class EtcdContext implements Context {

		@Override
		public boolean isLeader() {
			return isLeader;
		}

		@Override
		public void yield() {
			if (future != null) {
				future.cancel(true);
			}
		}

		@Override
		public String toString() {
			return String.format("EtcdContext{role=%s, id=%s, isLeader=%s}",
					candidate.getRole(), candidate.getId(), isLeader());
		}
		
	}

}
