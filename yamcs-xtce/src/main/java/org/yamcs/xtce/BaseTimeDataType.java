package org.yamcs.xtce;

public abstract class BaseTimeDataType extends BaseDataType {
    private static final long serialVersionUID = 2L;

    //these are defined in XTCE DataEncoding and are applicable only for time data types so we moved them here
    boolean needsScaling;
    double scale;
    double offset;
    
    BaseTimeDataType(String name){
	super(name);
    }

    /**
     * creates a shallow copy of t
     * @param t
     */
    protected BaseTimeDataType(BaseTimeDataType t) {
        super(t);    
        this.encoding = t.encoding;
    }
    

    public abstract Object parseString(String stringValue);
    
    public Object parseStringForRawValue(String stringValue) {
        return encoding.parseString(stringValue);
    }

    public void setScaling(boolean needsScaling, double offset, double scale) {
        this.needsScaling = needsScaling;
        this.offset = offset;
        this.scale = scale;
    }
    
    public boolean needsScaling() {
        return needsScaling;
    }
    public double getOffset() {
        return offset;
    }
    public double getScale() {
        return scale;
    }
}