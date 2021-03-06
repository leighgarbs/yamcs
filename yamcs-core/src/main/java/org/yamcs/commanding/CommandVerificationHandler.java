package org.yamcs.commanding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.Processor;
import org.yamcs.algorithms.AlgorithmExecutionContext;
import org.yamcs.algorithms.AlgorithmManager;
import org.yamcs.cmdhistory.CommandHistoryConsumer;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.Verifier.State;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.Value;
import org.yamcs.protobuf.Commanding.CommandHistoryAttribute;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.protobuf.Commanding.VerifierConfig;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.Argument;
import org.yamcs.xtce.CheckWindow;
import org.yamcs.xtce.CheckWindow.TimeWindowIsRelativeToType;
import org.yamcs.xtce.CommandVerifier;
import org.yamcs.xtce.CommandVerifier.TerminationAction;
import org.yamcs.xtce.MetaCommand;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.SequenceContainer;
import org.yamcs.xtce.XtceDb;

/**
 * This class implements the (post transmission) command verification.
 * <p>
 * There is one handler for all the verifiers of a command.
 * <p>
 * This handler collects all command attributes, command arguments and command history events and transforms them to
 * parameters to be given to the verifiers when they run.
 *
 */
public class CommandVerificationHandler implements CommandHistoryConsumer {
    final Processor yproc;
    final PreparedCommand preparedCommand;
    final ScheduledThreadPoolExecutor timer;
    private List<Verifier> verifiers = Collections.synchronizedList(new ArrayList<>());
    private final Logger log = LoggerFactory.getLogger(this.getClass().getName());
    AlgorithmExecutionContext algorithmCtx;

    // accumulate here all command attributes, arguments and command history events
    List<ParameterValue> cmdParameters = new ArrayList<>();

    public CommandVerificationHandler(Processor yproc, PreparedCommand pc) {
        this.yproc = yproc;
        this.preparedCommand = pc;
        this.timer = yproc.getTimer();
    }

    public void start() {
        collectCommandParameters();
        MetaCommand cmd = preparedCommand.getMetaCommand();
        List<CommandVerifier> cmdVerifiers = new ArrayList<>();
        collectCmdVerifiers(cmd, cmdVerifiers, preparedCommand.getVerifierOverride());
        
        if(preparedCommand.disableCommandVerifiers()) {
            log.debug("All verifiers are disabled");
            CommandHistoryPublisher cmdHistPublisher = yproc.getCommandHistoryPublisher();
            cmdVerifiers.forEach( cv -> cmdHistPublisher.publishAck(preparedCommand.getCommandId(), getHistKey(cv), yproc.getCurrentTime(),
                    AckStatus.DISABLED));
            return;
        }
        
        
        Verifier prevVerifier = null;

        try {
            yproc.getCommandHistoryManager().subscribeCommand(preparedCommand.getCommandId(), this);
        } catch (InvalidCommandId e) {
            log.error("Got invalidCommand id while subscribing for command history", e);
        }

        for (CommandVerifier cv : cmdVerifiers) {
            SequenceContainer c = cv.getContainerRef();
            Verifier verifier;

            if (c != null) {
                verifier = new ContainerVerifier(this, cv, c);
            } else {
                if (algorithmCtx == null) {
                    createAlgorithmContext();
                }
                verifier = new AlgorithmVerifier(this, cv);
            }
            CheckWindow checkWindow = cv.getCheckWindow();
            boolean scheduleNow = true;

            if (checkWindow.getTimeWindowIsRelativeTo() == TimeWindowIsRelativeToType.LastVerifier) {
                if (prevVerifier != null) {
                    prevVerifier.nextVerifier = verifier;
                    scheduleNow = false;
                }
            }
            verifiers.add(verifier);

            if (scheduleNow) {
                scheduleVerifier(verifier, checkWindow.getTimeToStartChecking(), checkWindow.getTimeToStopChecking());
            }
            prevVerifier = verifier;
        }
    }

    private void collectCommandParameters() {
        XtceDb xtcedb = yproc.getXtceDb();
        for (CommandHistoryAttribute cha : preparedCommand.getAttributes()) {
            String fqn = XtceDb.YAMCS_CMD_SPACESYSTEM_NAME + "/" + cha.getName();
            if (xtcedb.getParameter(fqn) == null) {
                // if it was required in the algorithm, it would be already in the system parameter db
                log.trace("Not adding {} to the context parameter list because it is not defined in the XtceDb", fqn);
                continue;
            }
            Parameter p = xtcedb.getParameter(fqn);

            ParameterValue pv = new ParameterValue(p);
            pv.setEngineeringValue(ValueUtility.fromGpb(cha.getValue()));
            cmdParameters.add(pv);
        }
        Map<Argument, Value> argAssignment = preparedCommand.getArgAssignment();
        for (Map.Entry<Argument, Value> e : argAssignment.entrySet()) {
            String fqn = XtceDb.YAMCS_CMD_SPACESYSTEM_NAME + "/arg/" + e.getKey().getName();
            if (xtcedb.getParameter(fqn) == null) {
                // if it was required in the algorithm, it would be already in the SystemParameterdb
                log.trace("Not adding {} to the context parameter list because it is not defined in the XtceDb", fqn);
                continue;
            }
            Parameter p = xtcedb.getParameter(fqn);

            ParameterValue pv = new ParameterValue(p);
            pv.setEngineeringValue(e.getValue());
            cmdParameters.add(pv);
        }

    }

    /**
     * collects all the required command verifiers from this command and its parents, taking care not to add two
     * verifiers for the same stage
     */
    private void collectCmdVerifiers(MetaCommand cmd, List<CommandVerifier> cmdVerifiers,
            Map<String, VerifierConfig> verifierOverride) {
        CommandHistoryPublisher cmdHistPublisher = yproc.getCommandHistoryPublisher();
        
        for (CommandVerifier cv : cmd.getCommandVerifiers()) {
            boolean found = false;
            for (CommandVerifier existingv : cmdVerifiers) {
                if (existingv.getStage().equals(cv.getStage())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                VerifierConfig extraOptions = verifierOverride.get(cv.getStage());
                if (extraOptions == null) {
                    cmdVerifiers.add(cv);
                } else {
                    if (extraOptions.getDisable()) {
                        cmdHistPublisher.publishAck(preparedCommand.getCommandId(), getHistKey(cv), yproc.getCurrentTime(),
                                AckStatus.DISABLED);
                        log.debug("skipping verifier {}", cv.getStage());
                        continue;
                    }
                    cmdVerifiers.add(overrideVerifier(cv, extraOptions));
                }
            }
        }
        MetaCommand basecmd = cmd.getBaseMetaCommand();
        if (basecmd != null) {
            collectCmdVerifiers(basecmd, cmdVerifiers, verifierOverride);
        }
    }

    private CommandVerifier overrideVerifier(CommandVerifier cv, VerifierConfig extraOptions) {
        if (!extraOptions.hasCheckWindow()) { // maybe we should throw an exception here
            return cv;
        }
        VerifierConfig.CheckWindow cw = extraOptions.getCheckWindow();
        CheckWindow cw1 = new CheckWindow(cw.getTimeToStartChecking(), cw.getTimeToStopChecking(),
                cv.getCheckWindow().getTimeWindowIsRelativeTo());
        CommandVerifier cv1 = new CommandVerifier(cv);
        cv1.setCheckWindow(cw1);
        log.debug("Replacing verifier {} with {}", cv, cv1);
        return cv1;
    }

    private void createAlgorithmContext() {
        AlgorithmManager algMgr = yproc.getParameterRequestManager().getParameterProvider(AlgorithmManager.class);
        if (algMgr == null) {
            String msg = "Algorithm manager not configured for this processor, cannot run command verification based on algorithms";
            log.error(msg);
            throw new ConfigurationException(msg);
        }

        algorithmCtx = algMgr.createContext(preparedCommand.getCmdName());
    }

    private void scheduleVerifier(final Verifier verifier, long windowStart, long windowStop) {
        CommandHistoryPublisher cmdHistPublisher = yproc.getCommandHistoryPublisher();
        String histKey = getHistKey(verifier.cv);

        if (windowStart > 0) {
            timer.schedule(() -> {
                if (verifier.state == State.NEW) {
                    cmdHistPublisher.publishAck(preparedCommand.getCommandId(), histKey, yproc.getCurrentTime(),
                            AckStatus.PENDING);

                    verifier.start();
                }
            }, windowStart, TimeUnit.MILLISECONDS);

            cmdHistPublisher.publishAck(preparedCommand.getCommandId(), histKey, yproc.getCurrentTime(),
                    AckStatus.SCHEDULED);
        } else {
            cmdHistPublisher.publishAck(preparedCommand.getCommandId(), histKey, yproc.getCurrentTime(),
                    AckStatus.PENDING);
            verifier.start();
        }

        if (windowStop <= 0) {
            throw new IllegalArgumentException("The window stop has to be greater than 0");
        }

        timer.schedule(() -> {
            verifier.timeout();
        }, windowStop, TimeUnit.MILLISECONDS);
    }

    String getHistKey( CommandVerifier cv) {
        return CommandHistoryPublisher.Verifier_KEY_PREFIX + "_" + cv.getStage();
    }
    void onVerifierFinished(Verifier v, String failureReason) {
        Verifier.State state = v.getState();
        log.debug("Command {} verifier finished: {} result: {}",
                StringConverter.toString(preparedCommand.getCommandId()), v.cv, state);
        CommandVerifier cv = v.cv;
        CommandHistoryPublisher cmdHistPublisher = yproc.getCommandHistoryPublisher();
        String histKey = CommandHistoryPublisher.Verifier_KEY_PREFIX + "_" + cv.getStage();
        cmdHistPublisher.publishAck(preparedCommand.getCommandId(), histKey, yproc.getCurrentTime(),
                getAckState(v.state), failureReason);
        TerminationAction ta = null;
        switch (state) {
        case OK:
            ta = cv.getOnSuccess();
            break;
        case NOK:
            ta = cv.getOnFail();
            break;
        case TIMEOUT:
            ta = cv.getOnTimeout();
            break;
        case CANCELLED:
            break;
        default:
            log.error("Illegal state onVerifierFinished called with state: {}", state);
        }
        if (ta == TerminationAction.SUCCESS) {
            cmdHistPublisher.publishAck(preparedCommand.getCommandId(), CommandHistoryPublisher.CommandComplete_KEY,
                    yproc.getCurrentTime(), AckStatus.OK);
            stop();
        } else if (ta == TerminationAction.FAIL) {
            if (failureReason == null) {
                failureReason = "Verifier " + cv.getStage() + " result: " + state;
            }

            cmdHistPublisher.commandFailed(preparedCommand.getCommandId(), yproc.getCurrentTime(), failureReason);
            stop();
        }

        if (v.nextVerifier != null && (state == State.OK)) {
            CheckWindow cw = v.nextVerifier.cv.getCheckWindow();
            scheduleVerifier(v.nextVerifier, cw.getTimeToStartChecking(), cw.getTimeToStopChecking());
        }
    }

    private AckStatus getAckState(State state) {
        switch (state) {
        case NEW:
        case RUNNING:
            return AckStatus.PENDING;
        case NOK:
            return AckStatus.NOK;
        case OK:
            return AckStatus.OK;
        case TIMEOUT:
            return AckStatus.TIMEOUT;
        case CANCELLED:
            return AckStatus.CANCELLED;
        case DISABLED:
            return AckStatus.DISABLED;
        default:
            throw new IllegalArgumentException("Unknown state " + state);
        }
    }

    private void stop() {
        log.debug("{} command verification finished", preparedCommand);
        yproc.getCommandHistoryManager().unsubscribeCommand(preparedCommand.getCommandId(), this);
    }

    public Processor getProcessor() {
        return yproc;
    }

    public AlgorithmExecutionContext getAlgorithmExecutionContext() {
        return algorithmCtx;
    }

    public PreparedCommand getPreparedCommand() {
        return preparedCommand;
    }

    public AlgorithmManager getAlgorithmManager() {
        return yproc.getParameterRequestManager().getParameterProvider(AlgorithmManager.class);
    }

    @Override
    public void addedCommand(PreparedCommand pc) {
    } // this will not be called because we subscribe to only one command

    // called from the command history when things are added in the stream
    @Override
    public void updatedCommand(CommandId cmdId, long changeDate, String key, Value value) {
        String fqn = XtceDb.YAMCS_CMDHIST_SPACESYSTEM_NAME + "/" + key;
        XtceDb xtcedb = yproc.getXtceDb();
        if (xtcedb.getParameter(fqn) == null) {
            // if it was required in the algorithm, it would be in the XtceDb
            log.trace("Not adding {} to the context parameter list because it is not defined in the XtceDb", fqn);
        } else {
            Parameter p = xtcedb.getParameter(fqn);
            ParameterValue pv = new ParameterValue(p);
            pv.setEngineeringValue(value);
            cmdParameters.add(pv);
            for (Verifier v : verifiers) {
                v.updatedCommandHistoryParam(pv);
            }
        }

        if (key.equals(CommandHistoryPublisher.CommandComplete_KEY + "_Status")) {
            log.trace("Command completed, canceling all pending verifiers");
            for (Verifier v : verifiers) {
                v.cancel();
            }
        }
    }

    /**
     * Returns the collected list of pseudo parameter values related to command: command properties, command arguments
     * and command history events.
     * 
     * Additional command history events may come later via updatedCommandHistoryParam
     * 
     */
    public List<ParameterValue> getCommandParameters() {
        return cmdParameters;
    }
}
