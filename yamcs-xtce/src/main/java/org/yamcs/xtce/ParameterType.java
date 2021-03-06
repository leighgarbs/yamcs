package org.yamcs.xtce;

import java.util.Collections;
import java.util.Set;

/**
 * Interface implemented by all the parameters types.
 * @author nm
 *
 */
public interface ParameterType extends DataType {
    /**    
     * 
     * @return the set of parameters on which this one depends in order to be extracted or alarm checked 
     *         can be an empty set if this parameter does not depend on any other
     */
    default Set<Parameter> getDependentParameters() {
        return Collections.emptySet();
    }

    /**
     * Whether this ParameterType has any alarms associated
     */
    boolean hasAlarm();

    /**
     * parses the string into a java object according to the parameter type
     * @param stringValue
     * @return
     */
    Object parseString(String stringValue);

    /**
     * parses the string into a java object according to the parameter encoding
     * @param stringValue
     * @return
     */
    Object parseStringForRawValue(String stringValue);
    
    /**
     * Set the data encoding for the parameter type
     * <br>
     * For arrays and aggregates types that do not have encoding;
     * this operation will throw an {@link UnsupportedOperationException}
     * 
     * @param dataEncoding
     */
    void setEncoding(DataEncoding dataEncoding);

    /**
     * Get the data encoding for the parameter type.
     * <br>
     * For arrays and aggregates types that do not have encoding;
     * this operation will throw an {@link UnsupportedOperationException}
     * @return
     */
    DataEncoding getEncoding();
    /**
     * Create a shallow copy of the parameter type
     *  - the object itself (and the primitive fields) are new 
     *    but the other fields reference to the same object like the original 
     *  
     * @return
     */
    ParameterType copy();
}
