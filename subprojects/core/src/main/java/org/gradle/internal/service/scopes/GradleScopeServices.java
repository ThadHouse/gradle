/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal.service.scopes;

import org.gradle.api.Action;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.InstantiatorFactory;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.cache.DefaultFileContentCacheFactory;
import org.gradle.api.internal.cache.FileContentCacheFactory;
import org.gradle.api.internal.changedetection.state.FileSystemMirror;
import org.gradle.api.internal.changedetection.state.InMemoryCacheDecoratorFactory;
import org.gradle.api.internal.file.FileLookup;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.delete.Deleter;
import org.gradle.api.internal.hash.FileHasher;
import org.gradle.api.internal.plugins.DefaultPluginManager;
import org.gradle.api.internal.plugins.ImperativeOnlyPluginApplicator;
import org.gradle.api.internal.plugins.PluginApplicator;
import org.gradle.api.internal.plugins.PluginManagerInternal;
import org.gradle.api.internal.plugins.PluginRegistry;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.options.OptionReader;
import org.gradle.api.invocation.Gradle;
import org.gradle.cache.CacheRepository;
import org.gradle.execution.BuildConfigurationAction;
import org.gradle.execution.BuildConfigurationActionExecuter;
import org.gradle.execution.BuildExecuter;
import org.gradle.execution.DefaultBuildConfigurationActionExecuter;
import org.gradle.execution.DefaultBuildExecuter;
import org.gradle.execution.DefaultTasksBuildExecutionAction;
import org.gradle.execution.DryRunBuildExecutionAction;
import org.gradle.execution.ExcludedTaskFilteringBuildConfigurationAction;
import org.gradle.execution.ProjectConfigurer;
import org.gradle.execution.SelectedTaskExecutionAction;
import org.gradle.execution.TaskGraphExecuter;
import org.gradle.execution.TaskNameResolvingBuildConfigurationAction;
import org.gradle.execution.TaskSelector;
import org.gradle.execution.commandline.CommandLineTaskConfigurer;
import org.gradle.execution.commandline.CommandLineTaskParser;
import org.gradle.execution.taskgraph.DefaultTaskGraphExecuter;
import org.gradle.execution.taskgraph.TaskPlanExecutor;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.Factory;
import org.gradle.internal.cleanup.BuildOperationBuildOutputDeleterDecorator;
import org.gradle.internal.cleanup.BuildOutputCleanupCache;
import org.gradle.internal.cleanup.BuildOutputCleanupRegistry;
import org.gradle.internal.cleanup.BuildOutputDeleter;
import org.gradle.internal.cleanup.DefaultBuildOutputCleanupCache;
import org.gradle.internal.cleanup.DefaultBuildOutputCleanupRegistry;
import org.gradle.internal.cleanup.DefaultBuildOutputDeleter;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.nativeplatform.filesystem.FileSystem;
import org.gradle.internal.progress.BuildOperationExecutor;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.resources.ResourceLockCoordinationService;
import org.gradle.internal.work.WorkerLeaseService;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static java.util.Arrays.asList;

/**
 * Contains the services for a given {@link GradleInternal} instance.
 */
public class GradleScopeServices extends DefaultServiceRegistry {

    private final CompositeStoppable registries = new CompositeStoppable();

    public GradleScopeServices(final ServiceRegistry parent, GradleInternal gradle) {
        super(parent);
        add(GradleInternal.class, gradle);
        addProvider(new TaskExecutionServices());
        register(new Action<ServiceRegistration>() {
            public void execute(ServiceRegistration registration) {
                for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
                    pluginServiceRegistry.registerGradleServices(registration);
                }
            }
        });
    }

    TaskSelector createTaskSelector(GradleInternal gradle, ProjectConfigurer projectConfigurer) {
        return new TaskSelector(gradle, projectConfigurer);
    }

    OptionReader createOptionReader() {
        return new OptionReader();
    }

    CommandLineTaskParser createCommandLineTaskParser(OptionReader optionReader, TaskSelector taskSelector) {
        return new CommandLineTaskParser(new CommandLineTaskConfigurer(optionReader), taskSelector);
    }

    BuildExecuter createBuildExecuter() {
        return new DefaultBuildExecuter(
                asList(new DryRunBuildExecutionAction(),
                        new SelectedTaskExecutionAction()));
    }

    BuildConfigurationActionExecuter createBuildConfigurationActionExecuter(CommandLineTaskParser commandLineTaskParser, TaskSelector taskSelector, ProjectConfigurer projectConfigurer) {
        List<BuildConfigurationAction> taskSelectionActions = new LinkedList<BuildConfigurationAction>();
        taskSelectionActions.add(new DefaultTasksBuildExecutionAction(projectConfigurer));
        taskSelectionActions.add(new TaskNameResolvingBuildConfigurationAction(commandLineTaskParser));
        return new DefaultBuildConfigurationActionExecuter(Arrays.asList(new ExcludedTaskFilteringBuildConfigurationAction(taskSelector)), taskSelectionActions);
    }

    ProjectFinder createProjectFinder(final GradleInternal gradle) {
        return new ProjectFinder() {
            public ProjectInternal getProject(String path) {
                return gradle.getRootProject().project(path);
            }

            @Override
            public ProjectInternal findProject(String path) {
                return gradle.getRootProject().findProject(path);
            }
        };
    }

    TaskGraphExecuter createTaskGraphExecuter(ListenerManager listenerManager, TaskPlanExecutor taskPlanExecutor, BuildCancellationToken cancellationToken, BuildOperationExecutor buildOperationExecutor, WorkerLeaseService workerLeaseService, ResourceLockCoordinationService coordinationService) {
        Factory<TaskExecuter> taskExecuterFactory = new Factory<TaskExecuter>() {
            @Override
            public TaskExecuter create() {
                return get(TaskExecuter.class);
            }
        };
        return new DefaultTaskGraphExecuter(listenerManager, taskPlanExecutor, taskExecuterFactory, cancellationToken, buildOperationExecutor, workerLeaseService, coordinationService);
    }

    ServiceRegistryFactory createServiceRegistryFactory(final ServiceRegistry services) {
        final Factory<LoggingManagerInternal> loggingManagerInternalFactory = getFactory(LoggingManagerInternal.class);
        return new ServiceRegistryFactory() {
            public ServiceRegistry createFor(Object domainObject) {
                if (domainObject instanceof ProjectInternal) {
                    ProjectScopeServices projectScopeServices = new ProjectScopeServices(services, (ProjectInternal) domainObject, loggingManagerInternalFactory);
                    registries.add(projectScopeServices);
                    return projectScopeServices;
                }
                throw new UnsupportedOperationException();
            }
        };
    }

    PluginRegistry createPluginRegistry(PluginRegistry parentRegistry) {
        return parentRegistry.createChild(get(GradleInternal.class).getClassLoaderScope());
    }

    PluginManagerInternal createPluginManager(Instantiator instantiator, GradleInternal gradleInternal, PluginRegistry pluginRegistry, InstantiatorFactory instantiatorFactory) {
        PluginApplicator applicator = new ImperativeOnlyPluginApplicator<Gradle>(gradleInternal);
        return instantiator.newInstance(DefaultPluginManager.class, pluginRegistry, instantiatorFactory.inject(this), applicator);
    }

    FileContentCacheFactory createFileContentCacheFactory(ListenerManager listenerManager, FileHasher fileHasher, FileSystem fileSystem, FileSystemMirror fileSystemMirror, CacheRepository cacheRepository, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory, Gradle gradle) {
        return new DefaultFileContentCacheFactory(listenerManager, fileHasher, fileSystem, fileSystemMirror, cacheRepository, inMemoryCacheDecoratorFactory, gradle);
    }

    protected BuildOutputCleanupRegistry createBuildOutputCleanupRegistry(FileResolver fileResolver) {
        return new DefaultBuildOutputCleanupRegistry(fileResolver);
    }

    protected BuildOutputDeleter createBuildOutputDeleter(BuildOperationExecutor buildOperationExecutor, GradleInternal gradle, FileResolver fileResolver, FileLookup lookup) {
        return new BuildOperationBuildOutputDeleterDecorator(gradle, buildOperationExecutor, new DefaultBuildOutputDeleter(new Deleter(fileResolver, lookup.getFileSystem())));
    }

    protected BuildOutputCleanupCache createBuildOutputCleanupCache(CacheRepository cacheRepository, GradleInternal gradle, BuildOutputDeleter buildOutputDeleter, BuildOutputCleanupRegistry buildOutputCleanupRegistry) {
        return new DefaultBuildOutputCleanupCache(cacheRepository, gradle, buildOutputDeleter, buildOutputCleanupRegistry);
    }

    @Override
    public void close() {
        registries.stop();
        super.close();
    }
}
