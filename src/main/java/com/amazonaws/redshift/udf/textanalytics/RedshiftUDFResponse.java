// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.redshift.udf.textanalytics;

import java.util.List;

/**
 * represents a Redshift UDF response
 * 
 * Example:
 * {
 *   "success": true,   // true indicates the call succeeded
 *   "error_msg" : "my function isn't working",  // shall only exist when success != true
 *   "num_records": 2,      // number of records in this payload
 *   "results" : [
 *      "s1",
 *      "s2"
 *    ]
 * }
 */

public class RedshiftUDFResponse {
    private boolean success = true;
    private String error_msg = null;
    private int num_records=0;
    private String[] results=null;

    public void setErrorMessage(String error_msg) {
        this.error_msg = error_msg;
        this.success = false;
    }

    public void setResults(String[] results) {
        this.results = results;
        this.num_records = results.length;
        this.success = true;
    }
}