// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.redshift.udf.textanalytics;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class HandlerRedactPIIEntities implements RequestHandler<Object, String>{
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    TextAnalyticsUDFHandler textAnalyticsUDFHandler = new TextAnalyticsUDFHandler();
    
    @Override
    public String handleRequest(Object event, Context context)
    {
        RedshiftUDFResponse redshiftUDFResponse = new RedshiftUDFResponse();
        RedshiftUDFEvent rsevent = gson.fromJson(gson.toJson(event), RedshiftUDFEvent.class);
        System.out.println(this.getClass().getName() + " - Records: " + rsevent.getNumRecords());
        try {
            String[] input = rsevent.getArgumentValuesAtPosition(0);
            String[] languageCodes = rsevent.getArgumentValuesAtPosition(1);
            String[] redactTypesArray = rsevent.getArgumentValuesAtPosition(2);
            String[] output = textAnalyticsUDFHandler.redact_pii_entities(input, languageCodes, redactTypesArray);
            redshiftUDFResponse.setResults(output);
        } 
        catch (Exception e) {
            System.out.println("ERROR: redact_pii_entities Exception\n" + e);
            redshiftUDFResponse.setErrorMessage(e.toString());
        }
        String outputJson = gson.toJson(redshiftUDFResponse);
        return outputJson;
    }
}

