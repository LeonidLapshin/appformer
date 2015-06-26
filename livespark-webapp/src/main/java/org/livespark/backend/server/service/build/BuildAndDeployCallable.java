package org.livespark.backend.server.service.build;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.enterprise.event.Event;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.maven.shared.invoker.InvocationResult;
import org.guvnor.common.services.project.builder.model.BuildMessage;
import org.guvnor.common.services.project.model.Project;
import org.jboss.errai.bus.server.api.ServerMessageBus;
import org.livespark.client.AppReady;

public class BuildAndDeployCallable extends BaseBuildCallable implements HttpSessionBindingListener {

    private final Event<AppReady> appReadyEvent;
    protected final HttpSession session;

    BuildAndDeployCallable( Project project,
                            File pomXml,
                            HttpSession session,
                            String queueSessionId,
                            ServletRequest sreq,
                            ServerMessageBus bus,
                            Event<AppReady> appReadyEvent ) {
        super( project, pomXml, queueSessionId, sreq, bus );
        this.session = session;
        this.appReadyEvent = appReadyEvent;
    }

    @Override
    protected List<BuildMessage> postBuildTasks( InvocationResult res ) throws Exception {
        final List<BuildMessage> messages = new ArrayList<BuildMessage>();
        messages.addAll( processBuildResults( res ) );
        messages.addAll( deployIfSuccessful( res ) );

        return messages;
    }

    protected List<BuildMessage> deployIfSuccessful( InvocationResult res ) throws MalformedURLException, URISyntaxException, IOException, Exception {
        if ( res.getExitCode() == 0 )
            deploy();

        return Collections.emptyList();
    }

    protected void deploy() throws MalformedURLException, URISyntaxException, IOException, Exception {
        final Collection<File> wars = getWarFiles();
        File deployDir = getDeployDir();
        for ( final File war : wars ) {
            sendOutputToClient("Deploying " + war.getName() + "...");
            deleteWarFile( deployDir, war );
            FileUtils.copyFileToDirectory( war, deployDir );

            FileAlterationMonitor monitor = new FileAlterationMonitor( 500 );
            IOFileFilter filter = FileFilterUtils.nameFileFilter( war.getName() + ".deployed" );
            FileAlterationObserver observer = new FileAlterationObserver( deployDir, filter );
            observer.addListener( new FileAlterationListenerAdaptor() {

                @Override
                public void onFileCreate( final File file ) {
                   fireAppReadyEvent(war, sreq);
                }

                @Override
                public void onFileChange(final File file) {
                    fireAppReadyEvent(war, sreq);
                }
            } );
            monitor.addObserver( observer );
            monitor.start();
        }
    }

    private void deleteWarFile( final File parent, final File war ) {
        FileUtils.deleteQuietly( new File( parent, war.getName() ) );
    }

    private File getTargetDir() {
        final File targetDir = new File( pomXml.getParent(), "/target" );
        return targetDir;
    }

    private Collection<File> getWarFiles( ) {
        final Collection<File> wars = FileUtils.listFiles( getTargetDir(), new String[]{"war"}, false );
        return wars;
    }

    private File getDeployDir() throws MalformedURLException,
                             URISyntaxException {
        String home = getWildflyHome();
        File deployDir = new File( home, "/standalone/deployments" );
        return deployDir;
    }

    private void fireAppReadyEvent(File war, ServletRequest sreq) {
        final String url = "http://" +
            sreq.getServerName() + ":" +
            sreq.getServerPort() + "/" +
            war.getName().replace( ".war", "" );

        appReadyEvent.fire( new AppReady( url ) );
    }

    @Override
    public void valueBound( HttpSessionBindingEvent event ) {
    }

    @Override
    public void valueUnbound( HttpSessionBindingEvent event ) {
        try {
            final File deploymentDir = getDeployDir();
            for ( final File war : getWarFiles() ) {
                deleteWarFile( deploymentDir, war );
            }
        } catch ( Throwable t ) {
            throw new RuntimeException( t );
        }
    }
}