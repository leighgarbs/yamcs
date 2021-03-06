package org.yamcs;

import java.util.concurrent.atomic.AtomicLong;

import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.commanding.CommandReleaser;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

import com.google.common.util.concurrent.AbstractService;

/**
 * Sends commands to yamcs streams
 * 
 * @author nm
 *
 */
public class StreamTcCommandReleaser extends AbstractYamcsService implements CommandReleaser {
    Stream stream;
    String streamName;
    String yamcsInstance;
    
    public void init(String yamcsInstance, YConfiguration config) throws ConfigurationException {
        this.yamcsInstance = yamcsInstance;
        if (!config.containsKey("stream")) {
            throw new ConfigurationException("Please specify the stream in the config (args)");
        }
        this.streamName = config.getString("stream");
    }

    @Override
    public void init(Processor proc) {
        try {
            proc.setCommandReleaser(this);
        } catch (ValidationException e) {
            throw new ConfigurationException(e.getMessage());
        }
    }

    @Override
    public void releaseCommand(PreparedCommand pc) {
        stream.emitTuple(pc.toTuple());
    }

    @Override
    protected void doStart() {
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        stream = ydb.getStream(streamName);
        if (stream == null) {
            ConfigurationException e = new ConfigurationException("Cannot find stream '" + streamName + "'");
            notifyFailed(e);
        } else {
            notifyStarted();
        }
    }

    @Override
    public void setCommandHistory(CommandHistoryPublisher commandHistoryPublisher) {

    }

    @Override
    protected void doStop() {
        notifyStopped();
    }
}
