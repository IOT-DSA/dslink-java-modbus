/*
 * ============================================================================
 * GNU General Public License
 * ============================================================================
 *
 * Copyright (C) 2006-2011 Serotonin Software Technologies Inc. http://serotoninsoftware.com
 * @author Matthew Lohbihler
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.serotonin.modbus4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.serotonin.modbus4j.base.KeyedModbusLocator;
import com.serotonin.modbus4j.base.ReadFunctionGroup;
import com.serotonin.modbus4j.base.SlaveAndRange;
import com.serotonin.modbus4j.locator.BaseLocator;

/**
 * A class for defining the information required to obtain in a batch.
 * 
 * The generic parameterization represents the class of the key that will be used to find the results in the BatchRead
 * object. Typically String would be used, but any Object is valid.
 * 
 * Some modbus devices have non-contiguous sets of values within a single register range. These gaps between values may
 * cause the device to return error responses if a request attempts to read them. In spite of this, because it is
 * generally more efficient to read a set of values with a single request, the batch read by default will assume that no
 * such error responses will be returned. If your batch request results in such errors, it is recommended that you
 * separate the offending request to a separate batch read object, or you can use the "contiguous requests" setting
 * which causes requests to be partitioned into only contiguous sets.
 * 
 * @author mlohbihler
 * @param <K>
 */
public class BatchRead<K> {
    private final List<KeyedModbusLocator<K>> requestValues = new ArrayList<>();

    /**
     * See documentation above.
     */
    private boolean contiguousRequests = false;

    /**
     * If this value is false, any error response received will cause an exception to be thrown, and the entire batch to
     * be aborted (unless exceptionsInResults is true - see below). If set to true, error responses will be set as the
     * result of all affected locators and the entire batch will be attempted with no such exceptions thrown.
     */
    private boolean errorsInResults = false;

    /**
     * If this value is false, any exceptions thrown will cause the entire batch to be aborted. If set to true, the
     * exception will be set as the result of all affected locators and the entire batch will be attempted with no such
     * exceptions thrown.
     */
    private boolean exceptionsInResults = false;

    /**
     * A batch may be split into an arbitrary number of individual Modbus requests, and so a given batch may take
     * an arbitrary amount of time to complete. The cancel field is provided to allow the batch to be cancelled.
     */
    private boolean cancel;

    /**
     * This is what the data looks like after partitioning.
     */
    private List<ReadFunctionGroup<K>> functionGroups;

    public boolean isContiguousRequests() {
        return contiguousRequests;
    }

    public void setContiguousRequests(boolean contiguousRequests) {
        this.contiguousRequests = contiguousRequests;
        functionGroups = null;
    }

    public boolean isErrorsInResults() {
        return errorsInResults;
    }

    public void setErrorsInResults(boolean errorsInResults) {
        this.errorsInResults = errorsInResults;
    }

    public boolean isExceptionsInResults() {
        return exceptionsInResults;
    }

    public void setExceptionsInResults(boolean exceptionsInResults) {
        this.exceptionsInResults = exceptionsInResults;
    }

    public List<ReadFunctionGroup<K>> getReadFunctionGroups(ModbusMaster master) {
        if (functionGroups == null)
            doPartition(master);
        return functionGroups;
    }

    public void addLocator(K id, BaseLocator<?> locator) {
        addLocator(new KeyedModbusLocator<>(id, locator));
    }

    private void addLocator(KeyedModbusLocator<K> locator) {
        requestValues.add(locator);
        functionGroups = null;
    }

    public boolean isCancel() {
        return cancel;
    }

    public void setCancel(boolean cancel) {
        this.cancel = cancel;
    }

    //
    //
    // Private stuff
    //
    private void doPartition(ModbusMaster master) {
        Map<SlaveAndRange, List<KeyedModbusLocator<K>>> slaveRangeBatch = new HashMap<>();

        // Separate the batch into slave ids and read functions.
        List<KeyedModbusLocator<K>> functionList;
        for (KeyedModbusLocator<K> locator : requestValues) {
            // Find the function list for this slave and range. Create it if necessary.
            functionList = slaveRangeBatch.get(locator.getSlaveAndRange());
            if (functionList == null) {
                functionList = new ArrayList<>();
                slaveRangeBatch.put(locator.getSlaveAndRange(), functionList);
            }

            // Add this locator to the function list.
            functionList.add(locator);
        }

        // Now that we have locators grouped into slave and function, check each read function group and break into
        // parts as necessary.
        Collection<List<KeyedModbusLocator<K>>> functionLocatorLists = slaveRangeBatch.values();
        FunctionLocatorComparator comparator = new FunctionLocatorComparator();
        functionGroups = new ArrayList<>();
        for (List<KeyedModbusLocator<K>> functionLocatorList : functionLocatorLists) {
            // Sort the list by offset.
            Collections.sort(functionLocatorList, comparator);

            // Break into parts by excessive request length. Remember the max item count that we can ask for, for
            // this function
            int maxReadCount = master.getMaxReadCount(functionLocatorList.get(0).getSlaveAndRange().getRange());

            // Create the request groups.
            createRequestGroups(functionGroups, functionLocatorList, maxReadCount);
            //System.out.println("requests: " + functionGroups.size());
        }
    }

    /**
     * We aren't trying to do anything fancy here, like some kind of artificial optimal group for performance or
     * anything. We pretty much just try to fit as many locators as possible into a single valid request, and then move
     * on.
     * 
     * This method assumes the locators have already been sorted by start offset.
     */
    private void createRequestGroups(List<ReadFunctionGroup<K>> functionGroups, List<KeyedModbusLocator<K>> locators,
            int maxCount) {
        ReadFunctionGroup<K> functionGroup;
        KeyedModbusLocator<K> locator;
        int index;
        int endOffset;
        // Loop for creation of groups.
        while (locators.size() > 0) {
            functionGroup = new ReadFunctionGroup<>(locators.remove(0));
            functionGroups.add(functionGroup);
            endOffset = functionGroup.getStartOffset() + maxCount - 1;

            // Loop for adding locators to the current group
            index = 0;
            while (locators.size() > index) {
                locator = locators.get(index);
                boolean added = false;

                if (locator.getEndOffset() <= endOffset) {
                    if (contiguousRequests) {
                        // The locator must at least abut the other locators in the group.
                        if (locator.getOffset() <= functionGroup.getEndOffset() + 1) {
                            functionGroup.add(locators.remove(index));
                            added = true;
                        }
                    }
                    else {
                        functionGroup.add(locators.remove(index));
                        added = true;
                    }
                }

                if (!added) {
                    // This locator does not fit inside the current function...
                    if (locator.getOffset() > endOffset)
                        // ... and since the list is sorted by offset, no other locators can either, so quit the loop.
                        break;

                    // ... but there still may be other locators that can, so increment the index
                    index++;
                }
            }
        }
    }

    class FunctionLocatorComparator implements Comparator<KeyedModbusLocator<K>> {
        @Override
        public int compare(KeyedModbusLocator<K> ml1, KeyedModbusLocator<K> ml2) {
            return ml1.getOffset() - ml2.getOffset();
        }
    }
}
