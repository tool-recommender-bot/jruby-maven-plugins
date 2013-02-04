package de.saumya.mojo.gem;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Date;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.sonatype.aether.RepositorySystemSession;

import de.saumya.mojo.ruby.script.ScriptException;

/**
 * goal to convert that artifact into a gem or uses a given gemspec to build a gem.
 * 
 * @goal package
 * @requiresDependencyResolution test
 */
public class PackageMojo extends AbstractGemMojo {

    /**
     * @parameter expression="${project.build.directory}"
     */
    File                              buildDirectory;

    /**
     * the gemspec to use for building the gem
     * <br/>
     * Command line -Dgemspec=...
     * 
     * @parameter default-value="${gemspec}"
     */
    File                              gemspec;

    /**
     * use the pom to generate a gemspec and overwrite the one in lauchDirectory.
     * <br/>
     * Command line -Dgemspec.overwrite=...
     * 
     * @parameter default-value="${gemspec.overwrite}"
     */
    boolean                           gemspecOverwrite = false;

    /** @parameter */
    private String                    date;

    /** @parameter */
    private String                    extraRdocFiles;

    /** @parameter */
    private String                    extraFiles;

    /** @parameter */
    private String                    rdocOptions;

    /** @parameter */
    private String                    requirePaths;

    /** @parameter */
    private String                    rubyforgeProject;

    /** @parameter */
    private String                    rubygemsVersion;

    /** @parameter */
    private String                    requiredRubygemsVersion;

    /** @parameter */
    private String                    bindir;

    /** @parameter */
    private String                    requiredRubyVersion;

    /** @parameter */
    private String                    postInstallMessage;

    /** @parameter */
    private String                    executables;

    /** @parameter */
    private String                    extensions;

    /** @parameter */
    private String                    platform;

    /** @parameter default-value="gem_hook.rb" */
    private String                    gemHook;

    /**
     * @parameter default-value="false"
     */
    boolean                           includeDependencies;

    /**
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    protected RepositorySystemSession repositorySession;

    @Override
    public void executeJRuby() throws MojoExecutionException,
            MojoFailureException, ScriptException {
        final MavenProject project = this.project;
        final GemArtifact artifact = new GemArtifact(project);
        try {
            // first case no gemspec file given but there is pom => use pom to generate gemspec
            if (this.gemspec == null && project.getBasedir() != null
                    && project.getBasedir().exists()) {
                // TODO generate the gemspec in the prepare-package phase so we
                // can use it separately
                buildFromPom(project, artifact);

                // use POM generated by polyglot maven instead of project
                // file (which can be 'Gemfile' or a gemspec file) to allow 
                // to install it into the local repository and be
                // usable for proper maven
                // maybe that is a bug in polyglot maven ?
                final File pom = new File(this.project.getFile()
                        .getAbsolutePath()
                        + ".pom");
                if (pom.exists()) {
                    this.project.setFile(pom);
                }

            }
            else {
                // no gemspec file given => find a gemspec file in launchDirectory
                if (this.gemspec == null) {
                    for (final File f : launchDirectory().listFiles()) {
                        if (f.getName().endsWith(".gemspec")) {
                            if (this.gemspec == null) {
                                this.gemspec = f;
                            }
                            else {
                                throw new MojoFailureException("more than one gemspec file found, use -Dgemspec=... to specifiy one");
                            }
                        }
                    }
                    if (this.gemspec == null) {
                        throw new MojoFailureException("no gemspec file or pom found, use -Dgemspec=... to specifiy a gemspec file or '-f ...' to use a pom file");
                    }
                    else {
                        getLog().info("use gemspec: " + this.gemspec);
                    }
                }

                // now we have a gemspec file - either found or given
                this.factory.newScriptFromJRubyJar("gem")
                        .addArg("build", this.gemspec)
                        .executeIn(launchDirectory());

                // find file with biggest lastModified
                File gemFile = null;
                for (final File f : launchDirectory().listFiles()) {
                    if (f.getName().endsWith(".gem")) {
                        if (gemFile == null || gemFile.lastModified() < f.lastModified() )
                        {
                            gemFile = f;
                        }
                    }
                }
                if (project.getFile() != null && artifact.isGem()) {
                    // only when the pom exist there will be an artifact
                    FileUtils.copyFileIfModified(gemFile, artifact.getFile());
                    gemFile.deleteOnExit();
                }
                else {
                    // keep the gem where it is when there is no buildDirectory
                    if (this.buildDirectory.exists()) {
                        FileUtils.copyFileIfModified(gemFile,
                                                     new File(this.buildDirectory,
                                                             gemFile.getName()));
                        gemFile.deleteOnExit();
                    }
                }
            }
        }
        catch (final IOException e) {
            throw new MojoExecutionException("error gemifing pom", e);
        }
    }

    private void buildFromPom(final MavenProject project, final GemArtifact artifact)
            throws MojoExecutionException, IOException, ScriptException {
        getLog().info("building gem for " + artifact + " . . .");
        getLog().info("include dependencies? " + this.includeDependencies);
        final File gemDir = new File(this.buildDirectory, artifact.getGemName());
        final File gemSpec = new File(gemDir, artifact.getGemName()
                + ".gemspec");
        final GemspecWriter gemSpecWriter = new GemspecWriter(gemSpec,
                project,
                artifact);

        if (this.date != null) {
            gemSpecWriter.append("date", Date.valueOf(this.date).toString());
        }
        gemSpecWriter.append("rubygems_version", this.rubygemsVersion);
        gemSpecWriter.append("required_rubygems_version",
                             this.requiredRubygemsVersion);
        gemSpecWriter.append("required_ruby_version", this.requiredRubyVersion);
        gemSpecWriter.append("bindir", this.bindir);
        gemSpecWriter.append("post_install_message", this.postInstallMessage);

        gemSpecWriter.append("rubyforge_project", this.rubyforgeProject);
        gemSpecWriter.appendRdocFiles(this.extraRdocFiles);
        gemSpecWriter.appendFiles(this.extraFiles);
        gemSpecWriter.appendList("executables", this.executables);
        gemSpecWriter.appendList("extensions", this.extensions);
        gemSpecWriter.appendList("rdoc_options", this.rdocOptions);
        gemSpecWriter.appendList("require_paths", this.requirePaths);
        final File rubyFile;
        if (artifact.hasJarFile()) {
            gemSpecWriter.appendPlatform(this.platform == null
                    ? "java"
                    : this.platform);
            gemSpecWriter.appendJarfile(artifact.getJarFile(),
                                        artifact.getJarFile().getName());
            final File lib = new File(gemDir, "lib");
            lib.mkdirs();
            // need relative filename here
            rubyFile = new File(lib.getName(), artifact.getGemName() + ".rb");
            gemSpecWriter.appendFile(rubyFile);
        }
        else {
            rubyFile = null;
            gemSpecWriter.appendPlatform(this.platform);
        }

        ArtifactResolutionResult jarDependencyArtifacts = null;
        if (this.includeDependencies) {
            final ArtifactFilter filter = new ArtifactFilter() {
                public boolean include(final Artifact candidate) {
                    if (candidate == artifact) {
                        return true;
                    }
                    final boolean result = (candidate.getType().equals("jar") && ("compile".equals(candidate.getScope()) || "runtime".equals(candidate.getScope())));
                    return result;
                }

            };

            // remember file location since resolve will set it to
            // local-repository location
            final File artifactFile = artifact.getFile();
            final ArtifactResolutionRequest request = new ArtifactResolutionRequest().setArtifact(project.getArtifact())
                    .setResolveRoot(false)
                    .setLocalRepository(this.localRepository)
                    .setRemoteRepositories(project.getRemoteArtifactRepositories())
                    .setCollectionFilter(filter)
                    .setManagedVersionMap(project.getManagedVersionMap())
                    .setArtifactDependencies(project.getDependencyArtifacts());
            jarDependencyArtifacts = this.repositorySystem.resolve(request);
            for (final Object element : jarDependencyArtifacts.getArtifacts()) {
                final Artifact dependency = (Artifact) element;
                getLog().info(" -- include -- " + dependency);
                gemSpecWriter.appendJarfile(dependency.getFile(),
                                            dependency.getFile().getName());
            }
            // keep the artifactFile on build directory
            artifact.setFile(artifactFile);
        }

        // TODO make it the maven way (src/main/ruby + src/test/ruby) or the
        // ruby way (lib + spec + test)
        // TODO make a loop or so ;-)
        final File binDir = new File(project.getBasedir(), "bin");
        final File libDir = new File(project.getBasedir(), "lib");
        final File generatorsDir = new File(project.getBasedir(), "generators");
        final File specDir = new File(project.getBasedir(), "spec");
        final File testDir = new File(project.getBasedir(), "test");

        if (binDir.exists()) {
            gemSpecWriter.appendPath("bin");
            for (final File file : binDir.listFiles()) {
                // if ( file.canExecute() ) { // java1.6 feature which will fail
                // on jre1.5 runtimes
                gemSpecWriter.appendExecutable(file.getName());
                // }
            }
        }
        if (libDir.exists()) {
            gemSpecWriter.appendPath("lib");
        }
        if (generatorsDir.exists()) {
            gemSpecWriter.appendPath("generators");
        }
        if (specDir.exists()) {
            gemSpecWriter.appendPath("spec");
            gemSpecWriter.appendTestPath("spec");
        }
        if (testDir.exists()) {
            gemSpecWriter.appendPath("test");
            gemSpecWriter.appendTestPath("test");
        }

        for (final Dependency dependency : project.getDependencies()) {
            if (!dependency.isOptional()
                    && dependency.getType().contains("gem")) {

                final String prefix = dependency.getGroupId()
                        .equals("rubygems") ? "" : dependency.getGroupId()
                        + ".";
                if ((Artifact.SCOPE_COMPILE + Artifact.SCOPE_RUNTIME).contains(dependency.getScope())) {
                    gemSpecWriter.appendDependency(prefix
                                                           + dependency.getArtifactId(),
                                                   dependency.getVersion());
                }
                else if ((Artifact.SCOPE_PROVIDED + Artifact.SCOPE_TEST).contains(dependency.getScope())) {
                    gemSpecWriter.appendDevelopmentDependency(prefix
                                                                      + dependency.getArtifactId(),
                                                              dependency.getVersion());
                }
                else {
                    // TODO put things into "requirements"
                }
            }
        }

        gemSpecWriter.close();

        gemSpecWriter.copy(gemDir);

        if (artifact.hasJarFile() && !rubyFile.exists()) {
            FileWriter writer = null;
            try {
                // need absolute filename here
                writer = new FileWriter(new File(gemDir, rubyFile.getPath()));

                writer.append("module ")
                        .append(titleizedClassname(project.getArtifactId()))
                        .append("\n");
                writer.append("  VERSION = '")
                        .append(artifact.getGemVersion())
                        .append("'\n");
                writer.append("  MAVEN_VERSION = '")
                        .append(project.getVersion())
                        .append("'\n");
                writer.append("end\n");
                writer.append("begin\n");
                writer.append("  require 'java'\n");
                writer.append("  require File.dirname(__FILE__) + '/")
                        .append(artifact.getJarFile().getName())
                        .append("'\n");
                if (jarDependencyArtifacts != null) {
                    for (final Object element : jarDependencyArtifacts.getArtifacts()) {
                        final Artifact dependency = (Artifact) element;
                        writer.append("  require File.dirname(__FILE__) + '/")
                                .append(dependency.getFile().getName())
                                .append("'\n");
                    }

                }
                writer.append("rescue LoadError\n");
                writer.append("  puts 'JAR-based gems require JRuby to load. Please visit www.jruby.org.'\n");
                writer.append("  raise\n");
                writer.append("end\n");
                writer.append("\n");
                writer.append("load File.dirname(__FILE__) + '/" + this.gemHook
                        + "' if File.exists?( File.dirname(__FILE__) + '/"
                        + this.gemHook + "')\n");
            }
            catch (final IOException e) {
                throw new MojoExecutionException("error writing ruby file", e);
            }
            finally {
                if (writer != null) {
                    try {
                        writer.close();
                    }
                    catch (final IOException ignore) {
                    }
                }
            }
        }

        final File localGemspec = new File(launchDirectory(), gemSpec.getName());

        this.factory.newScriptFromJRubyJar("gem")
                .addArg("build", gemSpec)
                .executeIn(gemDir);

        if ((!localGemspec.exists() || !FileUtils.contentEquals(localGemspec,
                                                                gemSpec))
                && this.gemspecOverwrite) {
            getLog().info("overwrite gemspec '" + localGemspec.getName() + "'");
            FileUtils.copyFile(gemSpec, localGemspec);
        }

        final StringBuilder gemFilename = new StringBuilder("rubygems".equals(artifact.getGroupId())
                ? ""
                : artifact.getGroupId() + ".").append(artifact.getArtifactId())
                .append("-")
                .append(artifact.getGemVersion())
                .append("java-gem".equals(artifact.getType()) || "java".equals(this.platform) ? "-java" : "")
                .append(".gem");

        FileUtils.copyFile(new File(gemDir, gemFilename.toString()),
                           artifact.getFile());
    }

    private String titleizedClassname(final String artifactId) {
        final StringBuilder name = new StringBuilder();
        for (final String part : artifactId.split("-")) {
            name.append(StringUtils.capitalise(part));
        }
        return name.toString();
    }

    @Override
    protected void executeWithGems() throws MojoExecutionException,
            ScriptException, IOException {
        // nothing to do here since we override executeJRuby
    }
}
