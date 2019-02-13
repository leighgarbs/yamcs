package org.yamcs.tctm.ccsds;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;

public class TmManagedParameters extends ManagedParameters {
    int frameLength;
    boolean frameErroControlPresent;
    int fshLength; //0 means not present
    
    enum ServiceType {
        PACKET,
        VCA_SDU
    };
    
    Map<Integer, TmVcManagedParameters> vcParams = new HashMap<>();
    

    public static TmManagedParameters parseConfig(YConfiguration config) {
        TmManagedParameters tmp = new TmManagedParameters();

        if (config.containsKey("physicalChannelName")) {
            tmp.physicalChannelName = config.getString("physicalChannelName");
        }
        tmp.frameLength = config.getInt("frameLength");
        if (tmp.frameLength < 8 || tmp.frameLength > 0xFFFF) {
            throw new ConfigurationException("Invalid frame length " + tmp.frameLength);
        }
        tmp.frameErroControlPresent = config.getBoolean("frameErroControlPresent");


        List<YConfiguration> l = config.getConfigList("virtualChannels");
        for (YConfiguration yc : l) {
           TmVcManagedParameters vmp = new TmVcManagedParameters(yc);
            if (tmp.vcParams.containsKey(vmp.vcId)) {
                throw new ConfigurationException("duplicate configuration of vcId " + vmp.vcId);
            }
            tmp.vcParams.put(vmp.vcId, vmp);
        }

      
        return tmp;
    }
    @Override
    public int getMaxFrameLength() {
        return frameLength;
    }
    @Override
    public int getMinFrameLength() {
        return frameLength;
    }
    
    @Override
    public Map<Integer, VirtualChannelHandler> createVcHandlers(String yamcsInstance, String linkName) {
        Map<Integer, VirtualChannelHandler> m = new HashMap<>();
        for (Map.Entry<Integer, TmVcManagedParameters> me : vcParams.entrySet()) {
            TmVcManagedParameters vmp = me.getValue();
            switch (vmp.service) {
            case PACKET:
                VirtualChannelPacketHandler vcph = new VirtualChannelPacketHandler(yamcsInstance, linkName+".vc"+vmp.vcId, vmp);
                m.put(vmp.vcId, vcph);
                break;
            case VCA_SDU:
                throw new UnsupportedOperationException("VCA_SDU not supported (TODO)");
            }
        }
        return m;
    }
    
    
    
    static class TmVcManagedParameters  extends VcManagedParameters {
        ServiceType service;
        
        public TmVcManagedParameters(YConfiguration config) {
            super(config);
            
            if (vcId < 0 || vcId > 7) {
                throw new ConfigurationException("Invalid vcId: " + vcId+". Allowed values are from 0 to 7.");
            }
            service = config.getEnum("service", ServiceType.class);
            
            if(service==ServiceType.PACKET) {
                parsePacketConfig();
            }
        }
    }

}
