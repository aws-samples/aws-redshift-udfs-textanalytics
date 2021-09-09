// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.redshift.udf.textanalytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * represents a Redshift UDF event
 * 
 * Example:
 *  {
 *     "request_id" : "23FF1F97-F28A-44AA-AB67-266ED976BF40",
 *     "cluster" : 'arn:aws:redshift:xxxx',
 *     "user" : "master",
 *     "database" : "db1",
 *     "external_function": "public.foo",
 *     "query_id" : 5678234,
 *     "num_records" : 2,
 *     "arguments" : [
 *        [ "string", "string" ],
 *        [ "string", "string" ]
 *      ]
 *    }
 */

public class RedshiftUDFEvent {
    private String request_id;
    private String cluster;
    private String user;
    private String external_function;
    private int query_id;
    private int num_records;
    private List<List<String>> arguments;

    public String getRequestId() {
        return request_id;
    }

    public String getCluster() {
        return cluster;
    }

    public String getUser() {
        return user;
    }

    public String getExternalFunction() {
        return external_function;
    }

    public int getQueryId() {
        return query_id;
    }

    public int getNumRecords() {
        return num_records;
    }
    
    public List<List<String>> getArguments() {
        return arguments;
    }   
    
    public String[] getArgumentValuesAtPosition(int position) {
        List<String> values = new ArrayList<String>();
        for (List<String> argument : arguments) {
            String value = argument.get(position);
            values.add(value);
        }
        return values.toArray(new String[0]);
    }
    
}