package hudson.maven.reporters;

import hudson.maven.MavenBuild;
import hudson.maven.MavenBuildProxy;
import hudson.maven.MavenBuildProxy.BuildCallable;
import hudson.maven.MavenModule;
import hudson.maven.MavenReporter;
import hudson.maven.MavenReporterDescriptor;
import hudson.maven.MojoInfo;
import hudson.model.BuildListener;
import hudson.model.Result;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.embedder.MavenEmbedderException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Archives artifacts of the build.
 *
 * <p>
 * Archive will be created in two places. One is inside the build directory,
 * to be served from Hudson. The other is to the local repository of the master,
 * so that artifacts can be shared in maven builds happening in other slaves.
 *
 * @author Kohsuke Kawaguchi
 */
public class MavenArtifactArchiver extends MavenReporter {
    private transient boolean installed;

    public boolean postExecute(MavenBuildProxy build, MavenProject pom, MojoInfo mojo, BuildListener listener, Throwable error) throws InterruptedException, IOException {
        if(!mojo.pluginName.matches("org.apache.maven.plugins","maven-install-plugin"))
            return true;
        if(!mojo.getGoal().equals("install"))
            return true;

        this.installed = true;

        return true;
    }

    public boolean postBuild(MavenBuildProxy build, MavenProject pom, final BuildListener listener) throws InterruptedException, IOException {
        if(pom.getFile()!=null) {// goals like 'clean' runs without loading POM, apparently.
            // record POM
            final MavenArtifact pomArtifact = new MavenArtifact(
                pom.getGroupId(), pom.getArtifactId(), pom.getVersion(), null, "pom", pom.getFile().getName());
            pomArtifact.archive(build,pom.getFile(),listener);

            // record main artifact (if packaging is POM, this doesn't exist)
            final MavenArtifact mainArtifact = MavenArtifact.create(pom.getArtifact());
            if(mainArtifact!=null)
                mainArtifact.archive(build,pom.getArtifact().getFile(),listener);

            // record attached artifacts
            final List<MavenArtifact> attachedArtifacts = new ArrayList<MavenArtifact>();
            for( Artifact a : (List<Artifact>)pom.getAttachedArtifacts() ) {
                MavenArtifact ma = MavenArtifact.create(a);
                if(ma!=null) {
                    ma.archive(build,a.getFile(),listener);
                    attachedArtifacts.add(ma);
                }
            }

            final boolean installed = this.installed;

            build.executeAsync(new BuildCallable<Void,IOException>() {
                public Void call(MavenBuild build) throws IOException, InterruptedException {
                    MavenArtifactRecord mar = new MavenArtifactRecord(build,pomArtifact,mainArtifact,attachedArtifacts);
                    build.addAction(mar);

                    mar.recordFingerprints();

                    // install files on the master
                    if(installed) {// TODO: find out how to install files on the master
                        try {
                            mar.install(listener);
                        } catch (MavenEmbedderException e) {
                            e.printStackTrace(listener.error(hudson.maven.reporters.Messages.MavenArtifactArchiver_FailedToInstallToMaster()));
                            build.setResult(Result.FAILURE);
                        } catch (ComponentLookupException e) {
                            e.printStackTrace(listener.error(Messages.MavenArtifactArchiver_FailedToInstallToMaster()));
                            build.setResult(Result.FAILURE);
                        } catch (ArtifactInstallationException e) {
                            e.printStackTrace(listener.error(Messages.MavenArtifactArchiver_FailedToInstallToMaster()));
                            build.setResult(Result.FAILURE);
                        }
                    }

                    return null;
                }
            });
        }

        return true;
    }

    public DescriptorImpl getDescriptor() {
        return DescriptorImpl.DESCRIPTOR;
    }

    public static final class DescriptorImpl extends MavenReporterDescriptor {
        public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

        private DescriptorImpl() {
            super(MavenArtifactArchiver.class);
        }

        public String getDisplayName() {
            return Messages.MavenArtifactArchiver_DisplayName();
        }

        public MavenReporter newAutoInstance(MavenModule module) {
            return new MavenArtifactArchiver();
        }
    }

    private static final long serialVersionUID = 1L;
}
