/*******************************************************************************
 * Copyright (c) 2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.internal.jobs.JobMessages;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.ProgressProvider;
import org.eclipse.jdt.ls.core.internal.CancellableProgressMonitor;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection.JavaLanguageClient;
import org.eclipse.jdt.ls.core.internal.ProgressReport;
import org.eclipse.jdt.ls.core.internal.ServiceStatus;
import org.eclipse.jdt.ls.core.internal.StatusReport;
import org.eclipse.jdt.ls.core.internal.managers.MavenProjectImporter;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Manager for creating {@link IProgressMonitor}s reporting progress to clients
 *
 * @author Fred Bricon
 *
 */
public class ProgressReporterManager extends ProgressProvider {

	private JavaLanguageClient client;
	private long delay;
	private PreferenceManager preferenceManager;

	public ProgressReporterManager(JavaLanguageClient client, PreferenceManager preferenceManager) {
		this.client = client;
		this.preferenceManager = preferenceManager;
		delay = 200;
	}

	@Override
	public IProgressMonitor createMonitor(Job job) {
		if (job.belongsTo(InitHandler.JAVA_LS_INITIALIZATION_JOBS)) {
			// for backward compatibility
			List<IProgressMonitor> monitors = Arrays.asList(new ServerStatusMonitor(), createJobMonitor(job));
			return new MulticastProgressReporter(monitors);
		}
		IProgressMonitor monitor = createJobMonitor(job);
		return monitor;
	}

	private IProgressMonitor createJobMonitor(Job job) {
		return new ProgressReporter(job);
	}

	@Override
	public IProgressMonitor getDefaultMonitor() {
		return new ProgressReporter();
	}

	public IProgressMonitor getProgressReporter(CancelChecker checker) {
		return new ProgressReporter(checker);
	}

	@Override
	public IProgressMonitor createProgressGroup() {
		return getDefaultMonitor();
	}

	//For Unit tests purposes
	public void setReportThrottle(long delay) {
		this.delay = delay;
	}

	private class MulticastProgressReporter implements IProgressMonitor {
		protected List<IProgressMonitor> monitors;

		public MulticastProgressReporter(List<IProgressMonitor> monitors) {
			this.monitors = monitors;
		}

		@Override
		public void done() {
			for (IProgressMonitor monitor : monitors) {
				monitor.done();
			}
		}

		@Override
		public boolean isCanceled() {
			for (IProgressMonitor monitor : monitors) {
				if (!monitor.isCanceled()) {
					return false;
				}
			}

			return true;
		}

		@Override
		public void beginTask(String name, int totalWork) {
			for (IProgressMonitor monitor : monitors) {
				monitor.beginTask(name, totalWork);
			}
		}

		@Override
		public void internalWorked(double work) {
			for (IProgressMonitor monitor : monitors) {
				monitor.internalWorked(work);
			}
		}

		@Override
		public void setCanceled(boolean cancelled) {
			for (IProgressMonitor monitor : monitors) {
				monitor.setCanceled(cancelled);
			}
		}

		@Override
		public void setTaskName(String name) {
			for (IProgressMonitor monitor : monitors) {
				monitor.setTaskName(name);
			}
		}

		@Override
		public void subTask(String name) {
			for (IProgressMonitor monitor : monitors) {
				monitor.subTask(name);
			}
		}

		@Override
		public void worked(int work) {
			for (IProgressMonitor monitor : monitors) {
				monitor.worked(work);
			}
		}
	}

	private class ProgressReporter extends CancellableProgressMonitor {

		protected static final String SEPARATOR = " - ";
		protected static final String IMPORTING_MAVEN_PROJECTS = "Importing Maven project(s)";
		protected Job job;
		protected int totalWork;
		protected String taskName;
		protected String subTaskName;
		protected int progress;
		protected long lastReport = 0;
		protected final String progressId;
		protected boolean clientReady = false;

		// TODO: Some progress reports are not very detailed
		// seems like an empty taskName is provided somtimes (why???)
		// if the taskName is empty, it would seem like we should ignore it (treat as background task)

		public ProgressReporter(CancelChecker checker) {
			super(checker);
			progressId = UUID.randomUUID().toString();
			// maybe just set the future as field instead
			// to guarantee everyone can subscribe and get notified correctly
			// but need to make sure we don't subscribe multiple times
			client.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(progressId)))
				.whenComplete((res, err) -> {
					// also check error
					if (isDone()) {
						client.notifyProgress(new ProgressParams(
							Either.forLeft(progressId),
							Either.forLeft(new WorkDoneProgressEnd())
						));
						return;
					}

					WorkDoneProgressBegin progress = new WorkDoneProgressBegin();
					progress.setTitle(taskName);
					progress.setMessage(subTaskName);
					progress.setPercentage(percentDone());
					client.notifyProgress(new ProgressParams(
						Either.forLeft(progressId),
						Either.forLeft(progress)
					));
					clientReady = true;
				});
		}

		public ProgressReporter() {
			this((CancelChecker) null);
		}

		// check cancellation? or why is this even a thing?
		public ProgressReporter(Job job) {
			this();
			this.job = job;
		}

		@Override
		public void setTaskName(String name) {
			super.setTaskName(name);
			taskName = name;
		}

		@Override
		public void beginTask(String task, int totalWork) {
			taskName = task;
			this.totalWork = totalWork;
			throttledProgress();
		}

		@Override
		public void subTask(String name) {
			this.subTaskName = name;
			if (IMPORTING_MAVEN_PROJECTS.equals(taskName) && (subTaskName == null || subTaskName.isEmpty())) {
				// completed or failed transfer
				return;
			}
			throttledProgress();
		}

		@Override
		public void worked(int work) {
			progress += work;
			throttledProgress();
		}

		@Override
		public void done() {
			super.done();
			if (!clientReady) {
				return;
			}
			client.notifyProgress(new ProgressParams(
				Either.forLeft(progressId),
				Either.forLeft(new WorkDoneProgressEnd())
			));
		}

		@Override
		public boolean isDone() {
			return super.isDone() || (totalWork > 0 && progress >= totalWork);
		}

		private Integer percentDone() {
			if (totalWork < 2) {
				// Indicates an unknown amount of progress
				return null;
			}
			return progress / totalWork * 100;
		}

		// don't skip early, just defer (so we don't miss a long running subtask after spam)
		private void throttledProgress() {
			if (!clientReady) {
				return;
			}
			long currentTime = System.currentTimeMillis();
			if (lastReport == 0 || isDone() || (currentTime - lastReport >= delay)) {
				lastReport = currentTime;
				WorkDoneProgressReport progress = new WorkDoneProgressReport();
				progress.setMessage(subTaskName);
				progress.setPercentage(percentDone());
				client.notifyProgress(new ProgressParams(
					Either.forLeft(progressId),
					Either.forLeft(progress)
				));
			}
		}

		private void sendProgress() {
			//Ignore system jobs or "The user operation is waiting for background work to complete." tasks
			if (job != null && job.isSystem() || JobMessages.jobs_blocked0.equals(taskName)) {
				return;
			}
			// throttle the sending of progress
			long currentTime = System.currentTimeMillis();
			if (lastReport == 0 || isDone() || (currentTime - lastReport >= delay)) {
				lastReport = currentTime;
				sendStatus();
			}
		}

		protected void sendStatus() {
			if (client == null || preferenceManager == null || preferenceManager.getClientPreferences() == null || !preferenceManager.getClientPreferences().isProgressReportSupported()) {
				return;
			}
			ProgressReport progressReport = new ProgressReport(progressId);
			String task = StringUtils.defaultIfBlank(taskName, (job == null || StringUtils.isBlank(job.getName())) ? "Background task" : job.getName());
			progressReport.setTask(task);
			progressReport.setSubTask(subTaskName);
			progressReport.setTotalWork(totalWork);
			progressReport.setWorkDone(progress);
			progressReport.setComplete(isDone());
			if (task != null && subTaskName != null && !subTaskName.isEmpty() && task.equals(MavenProjectImporter.IMPORTING_MAVEN_PROJECTS)) {
				progressReport.setStatus(task + SEPARATOR + subTaskName);
			} else {
				progressReport.setStatus(formatMessage(task));
			}
			client.sendProgressReport(progressReport);
		}


		protected String formatMessage(String task) {
			String message = getMessage(task);
			return (totalWork > 0) ? String.format("%.0f%% %s", ((double) progress / totalWork) * 100, message) : message;
		}

		protected String getMessage(String task) {
			String message = subTaskName == null || subTaskName.isEmpty() ? "" : subTaskName;
			return message;
		}
	}

	//XXX should we deprecate that class? doesn't seem to bring much value over the more generic ProgressReporter,
	//it's largely kept for legacy purposes.
	private class ServerStatusMonitor extends ProgressReporter {

		@Override
		protected String formatMessage(String task) {
			String message = getMessage(task);
			if (totalWork > 0 && !message.isEmpty()) {
				message = SEPARATOR + message;
			}
			return String.format("%.0f%% Starting Java Language Server%s", ((double) progress / totalWork) * 100, message);
		}

		@Override
		protected void sendStatus() {
			if (client == null) {
				return;
			}
			String task = StringUtils.defaultIfBlank(taskName, (job == null || StringUtils.isBlank(job.getName())) ? "Background task" : job.getName());
			String message = formatMessage(task);
			client.sendStatusReport(new StatusReport().withType(ServiceStatus.Starting.name()).withMessage(message));
		}

	}
}
