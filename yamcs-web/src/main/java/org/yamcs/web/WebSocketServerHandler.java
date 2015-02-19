package org.yamcs.web;


import java.io.IOException;
import java.io.StringWriter;
import java.util.regex.Pattern;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.protobuf.Pvalue.ParameterData;
import org.yamcs.protobuf.SchemaPvalue;
import org.yamcs.protobuf.Yamcs.ProtoDataType;

import com.dyuproject.protostuff.JsonIOUtil;
import com.dyuproject.protostuff.Schema;

/**
 * Handles handshakes and messages
 */
public class WebSocketServerHandler {

    final static Logger log=LoggerFactory.getLogger(WebSocketServerHandler.class.getName());

    static final String WEBSOCKET_PATH = "_websocket";
    private WebSocketServerHandshaker handshaker;
    public final int PROTOCOL_VERSION=1;
    public final int MESSAGE_TYPE_REQUEST=1;
    public final int MESSAGE_TYPE_REPLY=2;
    public final int MESSAGE_TYPE_EXCEPTION=3;
    public final int MESSAGE_TYPE_DATA=4;
    private int dataSeqCount=-1;

    final Pattern urlPattern=Pattern.compile("\\/([\\w\\-]+)\\/(.*)");
    
    JsonFactory jsonFactory=new JsonFactory();
    
    //these two are valid after the socket has been upgraded and they are practical final
    Channel channel;
    ParameterClient paraClient;
    
    void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest req, MessageEvent e, String yamcsInstance) throws Exception {
        //TODO: can we ever reach this twice???
        if(paraClient==null) {
            String applicationName;
            if (req.containsHeader(HttpHeaders.Names.USER_AGENT)) {
                applicationName = req.getHeader(HttpHeaders.Names.USER_AGENT);
                // Wish we could send appname from JS, but JS WebSocket API doesn't support custom http headers
                if (applicationName.contains("Mozilla")) {
                    applicationName = "uss-web";
                }
            } else {
                applicationName = "Unknown Client";
            }
            this.paraClient=new ParameterClient(yamcsInstance, this, applicationName);
        }

        this.channel=ctx.getChannel();

        // Handshake
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(this.getWebSocketLocation(yamcsInstance, req), null, false);
        this.handshaker = wsFactory.newHandshaker(req);
        if (this.handshaker == null) {
            wsFactory.sendUnsupportedWebSocketVersionResponse(ctx.getChannel());
        } else {
            this.handshaker.handshake(ctx.getChannel(), req).addListener(WebSocketServerHandshaker.HANDSHAKE_LISTENER);
        }
    }
    
   

    void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) throws JsonParseException, IOException {
        log.debug("received websocket frame {}", frame);
        // Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
            this.handshaker.close(ctx.getChannel(), (CloseWebSocketFrame) frame);
            return;
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
            return;
        } else if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }
       
        String jsrequest = ((TextWebSocketFrame) frame).getText();
        
        JsonParser jsp=jsonFactory.createJsonParser(jsrequest);
        if(jsp.nextToken()!=JsonToken.START_ARRAY) throw new RuntimeException("Invalid message (expecting an array)");
        if(jsp.nextToken()!=JsonToken.VALUE_NUMBER_INT) throw new RuntimeException("Invalid message (expecting version as an integer number)");
        int version=jsp.getIntValue();
        if(version!=PROTOCOL_VERSION) throw new RuntimeException("Invalid version (expecting "+PROTOCOL_VERSION+" received "+version);

        if(jsp.nextToken()!=JsonToken.VALUE_NUMBER_INT) throw new RuntimeException("Invalid message (expecting type as an integer number)");
        int messageType=jsp.getIntValue();
        if(messageType!=MESSAGE_TYPE_REQUEST) throw new RuntimeException("Invalid message type (expecting request="+MESSAGE_TYPE_REQUEST+" received "+messageType);

        
        if(jsp.nextToken()!=JsonToken.VALUE_NUMBER_INT) throw new RuntimeException("Invalid message (expecting seqId as an integer number)");
        int seqId=jsp.getIntValue();

        if(jsp.nextToken()!=JsonToken.START_OBJECT) throw new RuntimeException("Invalid message (expecting an object)");

        if((jsp.nextToken()!=JsonToken.FIELD_NAME) || (!"request".equals(jsp.getCurrentName())))
            throw new RuntimeException("Invalid message (expecting request as the first field)");
        if(jsp.nextToken()!=JsonToken.VALUE_STRING) throw new RuntimeException("Invalid message (expecting request as a string)");
        String request=jsp.getText();
       
       if((jsp.nextToken()!=JsonToken.FIELD_NAME) || (!"data".equals(jsp.getCurrentName())))
           throw new RuntimeException("Invalid message (expecting data as the next field)");
       
       paraClient.processRequest(request, seqId, jsp, this);
    }
    
    private String getWebSocketLocation(String yamcsInstance, HttpRequest req) {
        return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + "/"+ yamcsInstance+"/"+WEBSOCKET_PATH;
    }

    private void writeMessageStart(JsonGenerator g, int messageType, int seqId) throws IOException {
        g.writeStartArray();
        g.writeNumber(PROTOCOL_VERSION);
        g.writeNumber(messageType);
        g.writeNumber(seqId);
    }

    private void writeMessageEnd(JsonGenerator g) throws IOException {
        g.writeEndArray();
        g.close();
    }
    
    /**
     * sends a generic string exception message
     * @param requestId the request that has generated the exception
     * @param message the exception message
     */
    public void sendException(int requestId, String message) {
        StringWriter sw=new StringWriter();
        try {
            JsonGenerator g=jsonFactory.createJsonGenerator(sw);
            writeMessageStart(g,MESSAGE_TYPE_EXCEPTION, requestId);
            g.writeStartObject();
            g.writeStringField("et", "STRING");
            g.writeStringField("msg", message);
            g.writeEndObject();
            writeMessageEnd(g);
            //System.out.println("sending "+msg);
        } catch (IOException e) {
            log.error("Error encoding json object");
            return;
        }
        String msg=sw.toString();
        channel.write(new TextWebSocketFrame(msg));
    }

    /**
     * writes a structured exception message (e.g. for InvalidIdentificationException 
     * we want to pass the names of the invalid parameters
     */
    public <T> void sendException(int requestId, String exceptionType, T message, Schema<T> schema) {
        StringWriter sw=new StringWriter();
        try {
            JsonGenerator g=jsonFactory.createJsonGenerator(sw);
            writeMessageStart(g,MESSAGE_TYPE_EXCEPTION, requestId);
            g.writeStartObject();
            g.writeStringField("et", exceptionType);
            g.writeFieldName("msg");
            JsonIOUtil.writeTo(g, message, schema, false);
            g.writeEndObject();
            writeMessageEnd(g);
            //System.out.println("sending "+msg);
        } catch (IOException e) {
            log.error("Error encoding json object");
            return;
        }
        String msg=sw.toString();
        channel.write(new TextWebSocketFrame(msg));
    }

    public void sendReply(int requestId, String string, Object object) {
        StringWriter sw=new StringWriter();
        try {
            JsonGenerator g=jsonFactory.createJsonGenerator(sw);
            writeMessageStart(g,MESSAGE_TYPE_REPLY, requestId);
            writeMessageEnd(g);
        } catch (IOException e) {
            log.error("Error encoding JSON message ",e);
            return;
        }
        String msg=sw.toString();
        //System.out.println("sending "+msg);
        channel.write(new TextWebSocketFrame(msg));
    }

    public void sendData(ProtoDataType parameter, ParameterData pdata) throws IOException {
        dataSeqCount++;
        if(!channel.isOpen()) throw new IOException("Channel not open");
        
        if(!channel.isWritable()) {
            log.warn("Dropping message because channel is not writable");
            return;
        }
        
        StringWriter sw=new StringWriter();
        try {
            JsonGenerator g=jsonFactory.createJsonGenerator(sw);
            writeMessageStart(g,MESSAGE_TYPE_DATA, dataSeqCount);
            g.writeStartObject();
            g.writeStringField("dt", "ParameterData");
            g.writeFieldName("data");
            JsonIOUtil.writeTo(g, pdata, SchemaPvalue.ParameterData.WRITE, false);
            g.writeEndObject();
            writeMessageEnd(g);
            
        } catch (IOException e) {
            log.error("Error encoding JSON message ",e);
            return;
        }
        String msg=sw.toString();
        channel.write(new TextWebSocketFrame(msg));
    }



    public void channelDisconnected(Channel c) {
        if(paraClient!=null) {
            log.info("Channel "+c.getRemoteAddress()+" disconnected");
            paraClient.quit();
        }
    }
}