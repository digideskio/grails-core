/*
 * Copyright 2015 original authors
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

package org.grails.cli.profile.repository

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Dependency
import org.grails.cli.boot.GrailsDependencyVersions
import org.grails.cli.profile.Profile
import org.springframework.boot.cli.compiler.grape.AetherGrapeEngine
import org.springframework.boot.cli.compiler.grape.AetherGrapeEngineFactory
import org.springframework.boot.cli.compiler.grape.DependencyResolutionContext
import org.springframework.boot.cli.compiler.grape.DependencyResolutionFailedException
import org.springframework.boot.cli.compiler.grape.RepositoryConfiguration


/**
 *  Resolves profiles from a configured list of repositories using Aether
 *
 * @author Graeme Rocher
 * @since 3.1
 */
@CompileStatic
class MavenProfileRepository extends AbstractJarProfileRepository {

    public static final RepositoryConfiguration DEFAULT_REPO = new RepositoryConfiguration("grailsCentral", new URI("https://repo.grails.org/grails/core"), true)

    List<RepositoryConfiguration> repositoryConfigurations
    AetherGrapeEngine grapeEngine
    GroovyClassLoader classLoader
    DependencyResolutionContext resolutionContext
    private boolean resolved = false

    MavenProfileRepository(List<RepositoryConfiguration> repositoryConfigurations) {
        this.repositoryConfigurations = repositoryConfigurations
        classLoader = new GroovyClassLoader(Thread.currentThread().contextClassLoader)
        resolutionContext = new DependencyResolutionContext()
        this.grapeEngine = AetherGrapeEngineFactory.create(classLoader, repositoryConfigurations, resolutionContext)
        resolutionContext.addDependencyManagement(new GrailsDependencyVersions(grapeEngine))
    }

    MavenProfileRepository() {
        this([DEFAULT_REPO])
    }

    @Override
    Profile getProfile(String profileName) {
        String profileShortName = profileName
        if(profileName.contains(':')) {
            def art = new DefaultArtifact(profileName)
            profileShortName = art.artifactId
        }
        if(!resolved || !profilesByName.containsKey(profileShortName)) {
            return resolveProfile(profileName)
        }
        return super.getProfile(profileShortName)
    }

    protected Profile resolveProfile(String profileName) {
        Artifact art = getProfileArtifact(profileName)

        try {
            grapeEngine.grab(group: art.groupId, module: art.artifactId, version: art.version ?: null)
        } catch (DependencyResolutionFailedException e ) {

            def localData = new File(System.getProperty("user.home"),"/.m2/repository/${art.groupId.replace('.','/')}/$art.artifactId/maven-metadata-local.xml")
            if(localData.exists()) {
                def currentVersion = parseCurrentVersion(localData)
                def profileFile = new File(localData.parentFile, "$currentVersion/${art.artifactId}-${currentVersion}.jar")
                if(profileFile.exists()) {
                    classLoader.addURL(profileFile.toURI().toURL())
                }
                else {
                    throw e
                }
            }
            else {
                throw e
            }
        }

        processUrls()
        return super.getProfile(art.artifactId)
    }

    @CompileDynamic
    protected String parseCurrentVersion(File localData) {
        new XmlSlurper().parse(localData).versioning.versions.version[0].text()
    }

    protected void processUrls() {
        def urls = classLoader.getURLs()
        for (URL url in urls) {
            registerProfile(url, new URLClassLoader([url] as URL[], Thread.currentThread().contextClassLoader))
        }
    }

    @Override
    List<Profile> getAllProfiles() {
        if(!resolved) {
            List<Map> profiles = []
            resolutionContext.managedDependencies.each { Dependency dep ->
                if (dep.artifact.groupId == "org.grails.profiles") {
                    profiles.add([group: dep.artifact.groupId, module: dep.artifact.artifactId])
                }
            }
            profiles.sort { it.module }

            for (Map profile in profiles) {
                grapeEngine.grab(profile)
            }

            def localData = new File(System.getProperty("user.home"),"/.m2/repository/org/grails/profiles")
            if(localData.exists()) {
                localData.eachDir { File dir ->
                    if(!dir.name.startsWith('.')) {
                        def profileData = new File(dir, "/maven-metadata-local.xml")
                        if(profileData.exists()) {
                            def currentVersion = parseCurrentVersion(profileData)
                            def profileFile = new File(dir, "$currentVersion/${dir.name}-${currentVersion}.jar")
                            if(profileFile.exists()) {
                                classLoader.addURL(profileFile.toURI().toURL())
                            }
                        }
                    }
                }
            }

            processUrls()
            resolved = true
        }
        return super.getAllProfiles()
    }
}
