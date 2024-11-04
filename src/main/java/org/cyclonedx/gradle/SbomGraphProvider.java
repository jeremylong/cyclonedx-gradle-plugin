/*
 * This file is part of CycloneDX Gradle Plugin.
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
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.cyclonedx.gradle;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.cyclonedx.gradle.model.SbomComponent;
import org.cyclonedx.gradle.model.SbomComponentId;
import org.cyclonedx.gradle.model.SbomGraph;
import org.cyclonedx.gradle.utils.DependencyUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;

public class SbomGraphProvider implements Callable<SbomGraph> {

    private final Project project;
    private final CycloneDxTask task;

    public SbomGraphProvider(final Project project, final CycloneDxTask task) {
        this.project = project;
        this.task = task;
    }

    @Override
    public SbomGraph call() throws Exception {

        if (project.getGroup().equals("")
                || project.getName().isEmpty()
                || project.getVersion().equals("")) {
            throw new IllegalStateException("Project group and version are required for the CycloneDx task");
        }

        final DependencyGraphTraverser traverser = new DependencyGraphTraverser(
                project.getLogger(), getArtifacts(), new MavenProjectLookup(project), task);

        final Map<SbomComponentId, SbomComponent> graph = Stream.concat(
                        traverseParentProject(traverser), traverseChildProjects(traverser))
                .reduce(new HashMap<>(), DependencyUtils::mergeGraphs);

        return buildSbomGraph(graph);
    }

    private SbomGraph buildSbomGraph(final Map<SbomComponentId, SbomComponent> graph) {

        final Optional<SbomComponent> rootProject = DependencyUtils.findRootComponent(project, graph);
        if (rootProject.isPresent()) {
            DependencyUtils.connectRootWithSubProjects(
                    project, rootProject.get().getId(), graph);
            return new SbomGraph(graph, rootProject.get());
        } else {
            final SbomComponentId rootProjectId = new SbomComponentId(
                    project.getGroup().toString(),
                    project.getName(),
                    project.getVersion().toString(),
                    "");
            final SbomComponent sbomComponent = new SbomComponent.Builder()
                    .withId(rootProjectId)
                    .withDependencyComponents(new HashSet<>())
                    .withInScopeConfigurations(new HashSet<>())
                    .build();

            return new SbomGraph(graph, sbomComponent);
        }
    }

    private Stream<Map<SbomComponentId, SbomComponent>> traverseParentProject(
            final DependencyGraphTraverser traverser) {

        if (shouldSkipProject(project)) {
            return Stream.empty();
        }
        return project.getConfigurations().stream()
                .filter(configuration -> shouldIncludeConfiguration(configuration)
                        && !shouldSkipConfiguration(configuration)
                        && configuration.isCanBeResolved())
                .map(config -> traverser.traverseGraph(
                        config.getIncoming().getResolutionResult().getRoot(), project.getName(), config.getName()));
    }

    private Stream<Map<SbomComponentId, SbomComponent>> traverseChildProjects(
            final DependencyGraphTraverser traverser) {
        return project.getChildProjects().entrySet().stream()
                .flatMap(project -> project.getValue().getConfigurations().stream()
                        .filter(configuration -> shouldIncludeConfiguration(configuration)
                                && !shouldSkipConfiguration(configuration)
                                && configuration.isCanBeResolved())
                        .map(config -> traverser.traverseGraph(
                                config.getIncoming().getResolutionResult().getRoot(),
                                project.getKey(),
                                config.getName())));
    }

    private Map<ComponentIdentifier, File> getArtifacts() {
        return project.getAllprojects().stream()
                .filter(project -> !shouldSkipProject(project))
                .flatMap(project -> project.getConfigurations().stream())
                .filter(configuration -> shouldIncludeConfiguration(configuration)
                        && !shouldSkipConfiguration(configuration)
                        && configuration.isCanBeResolved())
                .flatMap(config -> config.getIncoming().getArtifacts().getArtifacts().stream())
                .collect(Collectors.toMap(
                        artifact -> artifact.getId().getComponentIdentifier(),
                        artifact -> artifact.getFile(),
                        (v1, v2) -> v1));
    }

    private boolean shouldSkipConfiguration(final Configuration configuration) {
        return task.getSkipConfigs().get().stream().anyMatch(configuration.getName()::matches);
    }

    private boolean shouldIncludeConfiguration(final Configuration configuration) {
        return task.getIncludeConfigs().get().isEmpty()
                || task.getIncludeConfigs().get().stream().anyMatch(configuration.getName()::matches);
    }

    private boolean shouldSkipProject(final Project project) {
        return task.getSkipProjects().get().contains(project.getName());
    }
}
