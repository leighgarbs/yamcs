package org.yamcs.ui.yamcsmonitor;

import org.yamcs.ConfigurationException;
import org.yamcs.api.YamcsConnector;
import org.yamcs.ui.ChannelControlClient;
import org.yamcs.ui.archivebrowser.ArchiveBrowser;
import org.yamcs.ui.archivebrowser.ArchiveIndexReceiver;
import org.yamcs.ui.archivebrowser.Selection;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.List;


/**
 * This is the archive browser that shows up in the yamcs monitor when pressing "Select Range From Archive"
 *
 */
public class ArchiveBrowserSelector extends ArchiveBrowser implements ActionListener {
    private static final long serialVersionUID = 1L;
    
    boolean isHrdpPlaying;
    //JMenuItem  showHistoMenuItem; 
    JMenuItem  showCindexMenuItem; 

    public ArchiveBrowserSelector(Component parent, YamcsConnector yconnector, ArchiveIndexReceiver indexReceiver, ChannelControlClient channelControl, boolean isAdmin) throws ConfigurationException, IOException {
        super(yconnector, indexReceiver, true);
        // create menus

        JMenuBar menuBar = new JMenuBar();
        setJMenuBar(menuBar);

        JMenu menu = new JMenu("File");
        menu.setMnemonic(KeyEvent.VK_F);
        menuBar.add(menu);
        JMenuItem closeMenuItem = new JMenuItem("Close", KeyEvent.VK_W);
        closeMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, ActionEvent.CTRL_MASK));
        closeMenuItem.getAccessibleContext().setAccessibleDescription("Close the window");
        closeMenuItem.addActionListener(this);
        closeMenuItem.setActionCommand("close");
        menu.add(closeMenuItem);

        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic(KeyEvent.VK_V);
        menuBar.add(viewMenu);

        // Export menu
        JMenu selectionMenu = new JMenu("Selection");
        menuBar.add(selectionMenu);

        packetRetrieval = new JMenuItem("Export Packets...");
        packetRetrieval.setEnabled(false);
        packetRetrieval.setToolTipText("Start packet retrieval of the selected packets");
        packetRetrieval.addActionListener(this);
        packetRetrieval.setActionCommand("start-packet-retrieval");
        selectionMenu.add(packetRetrieval);

        parameterRetrieval = new JMenuItem("Export Parameters...");
        parameterRetrieval.setEnabled(false);
        parameterRetrieval.setToolTipText("Start parameter retrieval for the selected time interval");
        parameterRetrieval.addActionListener(this);
        parameterRetrieval.setActionCommand("start-parameter-retrieval");
        selectionMenu.add(parameterRetrieval);

        cmdHistRetrieval = new JMenuItem("Export Command History...");
        cmdHistRetrieval.setEnabled(false);
        cmdHistRetrieval.setToolTipText("Start command history retrieval for the selected time interval");
        cmdHistRetrieval.addActionListener(this);
        cmdHistRetrieval.setActionCommand("start-cmdhist-retrieval");
        selectionMenu.add(cmdHistRetrieval);
        
        menuBar.add(getToolsMenu());
     
        viewMenu.addSeparator();

        if (isAdmin) {
            JMenuItem  dassArcReplayMenuItem = new JMenuItem("Show DaSS Archive Replay Command for Current Selection", KeyEvent.VK_D);
            dassArcReplayMenuItem.setEnabled(false);
            dassArcReplayMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, ActionEvent.CTRL_MASK));
            dassArcReplayMenuItem.getAccessibleContext().setAccessibleDescription(
            "Show the HLCL command for a Col-CC DaSS archive replay to copy it to the clipboard.");
            dassArcReplayMenuItem.addActionListener(this);
            dassArcReplayMenuItem.setActionCommand("show-dass-arc");
            viewMenu.add(dassArcReplayMenuItem);
        }
        JMenuItem  rawPacketDumpCmdMenuItem = new JMenuItem("Show Raw Packet Dump Command for Current Selection", KeyEvent.VK_R);
        rawPacketDumpCmdMenuItem.setEnabled(false);
        rawPacketDumpCmdMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, ActionEvent.CTRL_MASK));
        rawPacketDumpCmdMenuItem.getAccessibleContext().setAccessibleDescription(
        "Show the command line of a raw packet dump to copy it to the clipboard.");
        rawPacketDumpCmdMenuItem.addActionListener(this);
        rawPacketDumpCmdMenuItem.setActionCommand("show-raw-packet-dump");
        viewMenu.add(rawPacketDumpCmdMenuItem);

        archivePanel.openEntry("Telemetry");
        
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        archivePanel.replayPanel.applySelectionButton.addActionListener(this);
        archivePanel.replayPanel.setChannelControlClient(channelControl);
        archivePanel.replayPanel.clearReplayPanel();
        yconnector.addConnectionListener(this);
    }

    /*  void autofillPopup()
        {
            if ( currentHrdpChannelInfo != null ) {
                for (Enumeration<String> ep = payloads.keys(); ep.hasMoreElements(); ) {
                    final String key = ep.nextElement();
                    Vector<TMTypeSpec> tm = payloads.get(key);
                    for (Enumeration<TMTypeSpec> et = tm.elements(); et.hasMoreElements(); ) {
                        final TMTypeSpec pkt = et.nextElement();
                        pkt.enabled = currentHrdpChannelInfo.containsPacket(pkt.opsname);
                        pkt.assocMenuItem.setVisible(!pkt.enabled);
                    }
                }
                refreshTmDisplay();
            }
        }
     */
    @Override
    public void actionPerformed( ActionEvent ae ) {
        super.actionPerformed(ae);
        String cmd = ae.getActionCommand();
        if (cmd.equals("apply") ) {
            Selection sel = archivePanel.getSelection();
            if ( sel == null ) {
                showError("Select the range you want to apply. Then try again");
            } else {
                List<String> packets = archivePanel.getSelectedPackets("tm");
                ChannelWidget widget=YamcsMonitor.theApp.getActiveChannelWidget();
                if(widget instanceof ArchiveChannelWidget) {
                    ((ArchiveChannelWidget) widget).apply(archivePanel.getInstance(), sel.getStartInstant(), sel.getStopInstant(), packets.toArray(new String[0]));
                    showInfo("A new HRDP selection was applied.\n" +
                            "Look at the \"New Channel\" section in the Yamcs Monitor window to check.");
                } else {
                    showError("Cannot apply selection for the currently selected channel type");
                }
            }

            //} else if (cmd.equals("populate-from-current-channel")) {

            //autofillPopup();
        } else if (cmd.equalsIgnoreCase("close")) {
            setVisible(false);
        } else if (cmd.toLowerCase().endsWith("selection_finished") ) {
        	archivePanel.replayPanel.applySelectionButton.setEnabled(true);
        } else if(cmd.equalsIgnoreCase("selection_reset")) {
            archivePanel.replayPanel.applySelectionButton.setEnabled(false);
        } else if (cmd.equals("histo_selection_finished") ) {
            archivePanel.replayPanel.applySelectionButton.setEnabled(true);
        }
    }
}