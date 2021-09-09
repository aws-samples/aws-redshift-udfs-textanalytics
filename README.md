# Amazon Redshift UDFs for text translation and analytics using Amazon Comprehend and Amazon Translate

This Redshift UDF Lambda provides (i) text translation between languages using Amazon Translate, (ii) text analytics including detection of language, sentiment, entities and PII using Amazon Comprehend, and (iii) redaction of detected entities and PII.

### Deploying the UDFs

#### Install pre-built UDFs from the AWS Serverless Application Repository (SAR)

Install the prebuilt Lambda function with the following steps:
1.	Navigate to the [RedshiftTextAnalyticsUDF](https://console.aws.amazon.com/lambda/home?region=us-east-1#/create/app?applicationId=arn:aws:serverlessrepo:us-east-1:912625584728:applications/TextAnalyticsUDFHandler) application in the AWS Serverless Application Repository.
2.	In the Application settings section, keep the settings at their defaults.
3.	Select I acknowledge that this app creates custom IAM roles.
4.	Choose Deploy.
5.	When the application has deployed, chose **CloudFormation stack** from the Application **Deployments** tab
6.	Choose the stack **Outputs**
7.	Select the SQL code that is shown as the value of the output labelled **SQLScriptExternalFunction** - copy and paste this SQL into your Redshift SQL client or the Redshift console query editor.

Then try the query examples below, or examples of your own, using the UDF.

#### Build and Install UDF from source

1. From the project root dir, run `mvn clean install` if you haven't already.
3. From the project root dir, run  `./publish.sh <S3_BUCKET_NAME> redshift-udfs-textanalytics us-east-1` to publish the connector to your private AWS Serverless Application Repository. The S3_BUCKET in the command is where a copy of the connector's code will be stored for Serverless Application Repository to retrieve it. This will allow users with permission to do so, the ability to deploy instances of the connector via 1-Click form. Then navigate to [Serverless Application Repository](https://aws.amazon.com/serverless/serverlessrepo)
4. Deploy the lambda function from the serverless repo, or run `sam deploy --template-file packaged.yaml --stack-name RedshiftTextAnalyticsUDF --capabilities CAPABILITY_NAMED_IAM`
5. When the application has deployed, navigate to the **CloudFormation stack** named `RedshiftTextAnalyticsUDF` or `serverlessrepo-RedshiftTextAnalyticsUDF`
6.	Choose the stack **Outputs**
7.	Select the SQL code that is shown as the value of the output labelled **SQLScriptExternalFunction** - copy and paste this SQL into your Redshift SQL client or the Redshift console query editor.  
  
Then try the query examples below, or examples of your own, using the UDF.


#### How the UDF works
For more information about the Redshift UDF framework, see [Creating a scalar Lambda UDF](https://docs.aws.amazon.com/redshift/latest/dg/udf-creating-a-lambda-sql-udf.html).


The Java class [TextAnalyticsUDFHandler](./src/main/java/com/amazonaws/redshift/udf/textanalytics/TextAnalyticsUDFHandler.java) implements the core logic for each of our UDF 
Lambda function handlers. Each text analytics function has a corresponding public method in this class. 

Redshift invokes our UDF Lambda function with batches of input records. The TextAnalyticsUDFHandler subdivides these batches into smaller batches of up to 25 rows to take advantage of the Amazon Comprehend synchronous multi-document batch APIs where they are available (for example, for detecting language, entities, and sentiment). When there is no synchronous multi-document API available (such as for DetectPiiEntity and TranslateText), we use the single-document API instead.

Amazon Comprehend API [service quotas](https://docs.aws.amazon.com/comprehend/latest/dg/guidelines-and-limits.html) provide guardrails to limit your cost exposure from unintentional high usage (we discuss this more in the following section). By default, the multi-document batch APIs process up to 250 records per second, and the single-document APIs process up to 20 records per second. Our UDFs use exponential back off and retry to throttle the request rate to stay within these limits. You can request increases to the transactions per second quota for APIs using the Quota Request Template on the AWS Management Console.

Amazon Comprehend and Amazon Translate each enforce a maximum input string length of 5,000 utf-8 bytes. Text fields that are longer than 5,000 utf-8 bytes are truncated to 5,000 bytes for language and sentiment detection, and split on sentence boundaries into multiple text blocks of under 5,000 bytes for translation and entity or PII detection and redaction. The results are then combined.

#### Optimizing cost
In addition to Amazon Redshift costs, the text analytics UDF incurs usage costs from Lambda and Amazon Comprehend and Amazon Translate. The amount you pay is a factor of the total number of records and characters that you process with the UDF. For more information, see [AWS Lambda pricing](https://aws.amazon.com/lambda/pricing/), [Amazon Comprehend pricing](https://aws.amazon.com/comprehend/pricing/), and [Amazon Translate pricing](https://aws.amazon.com/translate/pricing/).

To minimize the costs, avoid processing the same records multiple times. Instead, materialize the results of the text analytics UDF in a table that you can then cost-effectively query as often as needed without incurring additional UDF charges.  Process newly arriving records incrementally using INSERT INTO…SELECT queries to analyze and enrich only the new records and add them to the target table. 

Avoid calling the text analytics functions needlessly on records that you will subsequently discard. Write your queries to filter the dataset first using temporary tables, views, or nested queries, and then apply the text analytics functions to the resulting filtered records. 

Always assess the potential cost before you run text analytics queries on tables with vary large numbers of records. 

Here are two example cost assessments:

**Example 1: Analyze the language and sentiment of tweets**  

Let’s assume you have 10,000 tweet records, with average length 100 characters per tweet. Your SQL query detects the dominant language and sentiment for each tweet. You’re in your second year of service (the Free Tier no longer applies). The cost details are as follows:

- Size of each tweet = 100 characters
- Number of units (100 character) per record (minimum is 3 units) = 3
- Total Units: 10,000 (records) x 3 (units per record) x 2 (Amazon Comprehend requests per record) = 60,000
- Price per unit = $0.0001
- Total cost for Amazon Comprehend = [number of units] x [cost per unit] = 60,000 x $0.0001 = $6.00   

**Example 2: Translate tweets**  

Let’s assume that 2,000 of your tweets aren’t in your local language, so you run a second SQL query to translate them. The cost details are as follows:

- Size of each tweet = 100 characters
- Total characters: 2,000 (records) * 100 (characters per record) x 1 (Translate requests per record) = 200,000
- Price per character = $0.000015
- Total cost for Amazon Translate = [number of characters] x [cost per character] = 200,000 x $0.000015 = $3.00
 

## Functions

### Translate Text

#### translate\_text(text_col VARCHAR, sourcelang VARCHAR, targetlang VARCHAR, terminologyname VARCHAR) RETURNS VARCHAR

Returns the translated string, in the target language specified. Source language can be explicitly specified, or use 'auto'
to detect source language automatically (the Translate service calls Comprehend behind the scenes to detect the source language when you use 'auto'). 
Specify a custom terminology name, or 'null' if you aren't using custom terminologies.
```
SELECT translate_text('It is a beautiful day in the neighborhood', 'auto', 'fr', 'null') as translated_text

translated_text
C'est une belle journée dans le quartier
```

### Detect Language

#### detect\_dominant\_language(text_col VARCHAR) RETURNS VARCHAR

Returns string value with dominant language code:
```
SELECT detect_dominant_language('il fait beau à Orlando') as language

language
fr
```
#### detect\_dominant\_language\_all(text_col VARCHAR) RETURNS VARCHAR

Returns the set of detected languages and scores as a JSON formatted string, which can be further analysed with Redshift's [JSON functions](https://docs.aws.amazon.com/redshift/latest/dg/json-functions.html).
```
SELECT detect_dominant_language_all('il fait beau à Orlando') as language_all

language_all
[{"languageCode":"fr","score":0.99807304}]
```

### Detect Sentiment

Input languages supported: en | es | fr | de | it | pt | ar | hi | ja | ko | zh | zh-TW (See [doc](https://docs.aws.amazon.com/comprehend/latest/dg/API_DetectSentiment.html#comprehend-DetectSentiment-request-LanguageCode) for latest)

#### detect\_sentiment(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns string value with dominant sentiment:

```
SELECT detect_sentiment('Joe is very happy', 'en') as sentiment

sentiment
POSITIVE
```

#### detect\_sentiment\_all(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns the dominant sentiment and all sentiment scores as a JSON formatted string, which can be further analysed with Redshift's [JSON functions](https://docs.aws.amazon.com/redshift/latest/dg/json-functions.html).

```
SELECT detect_sentiment_all('Joe is very happy', 'en') as sentiment_all

sentiment_all
{"sentiment":"POSITIVE","sentimentScore":{"positive":0.999519,"negative":7.407639E-5,"neutral":2.7478999E-4,"mixed":1.3210243E-4}}
```

### Detect and Redact Entities

Entity Types supported -- see [Entity types](https://docs.aws.amazon.com/comprehend/latest/dg/how-entities.html)
Input languages supported: en | es | fr | de | it | pt | ar | hi | ja | ko | zh | zh-TW (See [doc](https://docs.aws.amazon.com/comprehend/latest/dg/API_BatchDetectEntities.html#API_BatchDetectEntities_RequestSyntax) for latest)


#### detect\_entities(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns JSON string value with list of PII types and values:

```
SELECT detect_entities('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en') as entities

entities
[["PERSON","Joe"],["LOCATION","Richmond VA"],["ORGANIZATION","Amazon"],["COMMERCIAL_ITEM","Echo Show"],["DATE","January 5th"]]
```

#### detect\_entities\_all(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns the detected entity types, scores, values, and offsets as a JSON formatted string, which can be further analysed with Redshift's [JSON functions](https://docs.aws.amazon.com/redshift/latest/dg/json-functions.html).

```
SELECT detect_entities_all('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en') as entities_all

entities_all
[{"score":0.9956949,"type":"PERSON","text":"Joe","beginOffset":12,"endOffset":15},{"score":0.99672645,"type":"LOCATION","text":"Richmond VA","beginOffset":29,"endOffset":40},{"score":0.963684,"type":"ORGANIZATION","text":"Amazon","beginOffset":55,"endOffset":61},{"score":0.98822284,"type":"COMMERCIAL_ITEM","text":"Echo Show","beginOffset":62,"endOffset":71},{"score":0.998659,"type":"DATE","text":"January 5th","beginOffset":75,"endOffset":86}]
```

#### redact\_entities(text_col VARCHAR, lang VARCHAR, type VARCHAR) RETURNS VARCHAR

Redacts specified entity values from the input string.
Use the `types` argument to specify a list of [PII types](https://docs.aws.amazon.com/comprehend/latest/dg/API_PiiEntity.html#comprehend-Type-PiiEntity-Type) to be redacted.  

```
-- redact PERSON
SELECT redact_entities('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en', 'PERSON') as entities_redacted

entities_redacted
His name is [PERSON], he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it

-- redact PERSON and DATE
SELECT redact_entities('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en', 'PERSON, DATE') as entities_redacted

entities_redacted
His name is [PERSON], he lives in Richmond VA, he bought an Amazon Echo Show on [DATE], and he loves it

-- redact ALL Entity types
SELECT redact_entities('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en', 'ALL') as entities_redacted

entities_redacted
His name is [PERSON], he lives in [LOCATION], he bought an [ORGANIZATION] [COMMERCIAL_ITEM] on [DATE], and he loves it
```


### Detect and Redact PII

PII Types supported -- see [PII types](https://docs.aws.amazon.com/comprehend/latest/dg/API_PiiEntity.html#comprehend-Type-PiiEntity-Type)
Input languages supported: 'en' (See [doc](https://docs.aws.amazon.com/comprehend/latest/dg/API_DetectPiiEntities.html#comprehend-DetectPiiEntities-request-LanguageCode) for latest)


#### detect\_pii\_entities(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns JSON string value with list of PII types and values:

```
SELECT detect_pii_entities('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en') as pii

pii
[["NAME","Joe"],["USERNAME","joe123"],["ADDRESS","Richmond VA"]]
```

#### detect\_pii\_entities\_all(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns the detected PII types, scores, and offsets as a JSON formatted string, which can be further analysed with Redshift's [JSON functions](https://docs.aws.amazon.com/redshift/latest/dg/json-functions.html).

```
SELECT detect_pii_entities_all('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en') as pii_all

pii_all
[{"score":0.999894,"type":"NAME","beginOffset":12,"endOffset":15},{"score":0.99996245,"type":"USERNAME","beginOffset":33,"endOffset":39},{"score":0.9999982,"type":"ADDRESS","beginOffset":56,"endOffset":67}]
```

#### redact\_pii\_entities(text_col VARCHAR, lang VARCHAR, type VARCHAR) RETURNS VARCHAR

Redacts specified entity values from the input string.
Use the `types` argument to specify a list of [PII types](https://docs.aws.amazon.com/comprehend/latest/dg/API_PiiEntity.html#comprehend-Type-PiiEntity-Type) to be redacted.  

```
-- redact name
SELECT redact_pii_entities('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en', 'NAME') as pii_redacted

pii_redacted
His name is [NAME], his username is joe123 and he lives in Richmond VA

-- redact NAME and ADDRESS
SELECT redact_pii_entities('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en', 'NAME,ADDRESS') as pii_redacted

pii_redacted
His name is [NAME], his username is joe123 and he lives in [ADDRESS]

-- redact ALL PII types
SELECT redact_pii_entities('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en', 'ALL') as pii_redacted

pii_redacted
His name is [NAME], his username is [USERNAME] and he lives in [ADDRESS]
```


## Use case examples

#### Analyze Amazon Product Reviews - sentiment by language, and detect PII

Dataset: See https://s3.amazonaws.com/amazon-reviews-pds/readme.html 

1. Configure Amazon Redshift Spectrum and create external schema (if you've not previously done so)  

    1. Create an IAM role for your Amazon Redshift cluster 
        1. Open the [IAM console](https://console.aws.amazon.com/iam/home?#home).
        1. In the navigation pane, choose **Roles**.
        1. Choose **Create role**.
        1. Choose **AWS service**, and then choose **Redshift**.
        1. Under **Select your use case**, choose **Redshift - Customizable** and then choose **Next: Permissions**.
        1. The **Attach permissions** policy page appears. Choose `AmazonS3ReadOnlyAccess` and `AWSGlueConsoleFullAccess`, if you're using the AWS Glue Data Catalog. Or choose `AmazonAthenaFullAccess` if you're using the Athena Data Catalog. Choose Next: Review.
        1. For **Role name**, enter a name for your role, for example `mySpectrumRole`.
        1. Review the information, and then choose **Create role**.
        1. In the navigation pane, choose **Roles**. Choose the name of your new role to view the summary, and then copy the **Role ARN** to your clipboard. This value is the Amazon Resource Name (ARN) for the role that you just created. You use that value when you create external tables to reference your data files on Amazon S3.  

    2. Associate the IAM role with your cluster
        1. Sign in to the AWS Management Console and open the Amazon Redshift console at https://console.aws.amazon.com/redshift/.
        1. On the navigation menu, choose **CLUSTERS**, then choose the name of the cluster that you want to update.
        1. For **Actions**, choose **Manage IAM roles**. The **IAM roles** page appears.
        1. Either Choose **Enter ARN** and then enter an ARN or an IAM role, or choose an IAM role from the list. Then choose **Add IAM** role to add it to the list of **Attached IAM roles**.
        1. Choose **Done** to associate the IAM role with the cluster. The cluster is modified to complete the change.  

    3. Create an external schema
        1. To create an external schema called `spectrum`, replace the IAM role ARN in the following command with the role ARN you created in step 1. Then run the command below on your Redshift cluster using your SQL client or the Redshift console query editor.  
            ```
            create external schema spectrum 
            from data catalog 
            database 'spectrum' 
            iam_role 'arn:aws:iam::123456789012:role/mySpectrumRole'
            create external database if not exists;
            ```

2. Create external table to access product reviews

    1. Create external table called `spectrum.amazon_reviews_parquet`
        Run the SQL below on your Redshift cluster using your SQL client or the Redshift console query editor. 
        ```
        CREATE EXTERNAL TABLE spectrum.amazon_reviews_parquet(
          marketplace VARCHAR, 
          customer_id VARCHAR, 
          review_id VARCHAR, 
          product_id VARCHAR, 
          product_parent VARCHAR, 
          product_title VARCHAR, 
          star_rating int, 
          helpful_votes int, 
          total_votes int, 
          vine VARCHAR, 
          verified_purchase VARCHAR, 
          review_headline VARCHAR, 
          review_body VARCHAR, 
          review_date bigint, 
          year int)
        PARTITIONED BY (product_category VARCHAR)
        ROW FORMAT SERDE 
          'org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe' 
        STORED AS INPUTFORMAT 
          'org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat' 
        OUTPUTFORMAT 
          'org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat'
        LOCATION
          's3://amazon-reviews-pds/parquet/'
        ```
    2. Load table partitions using Amazon Athena
        1. Open the Amazon Athena console at https://console.aws.amazon.com/athena/
        1. Choose `spectrum` from the **Databases** selector on the left toolbar
        1. Choose **Load partitions** from the `amazon_reviews_parquet` table menu
            ![LoadPartitions](./images/load_partitions.png)


3. Copy a subset of 5000 Amazon product reviews to Redshift  
    Execute all the following SQL statements on your Redshift cluster using your SQL client or the Redshift console Editor.
    ```
    CREATE TABLE amazon_reviews_enriched AS
    SELECT *
    FROM spectrum.amazon_reviews_parquet
    LIMIT 5000
    ```

4. Add and populate detected languages column
    ```
    ALTER TABLE amazon_reviews_enriched ADD COLUMN language VARCHAR;
    UPDATE amazon_reviews_enriched 
    SET language = detect_dominant_language(review_headline);
    ```

5. Add and populate sentiment column   
    ```
    ALTER TABLE amazon_reviews_enriched ADD COLUMN sentiment VARCHAR;
    UPDATE amazon_reviews_enriched 
    SET sentiment = detect_sentiment(review_headline, language) 
    WHERE language in ('ar', 'hi', 'ko', 'zh-TW', 'ja', 'zh', 'de', 'pt', 'en', 'it', 'fr', 'es');
    ```
    *Note: The query is constrained to reviews written in the set of languages supported by Comprehend's detectSentiment API.  


6. Explore results

    ```
    SELECT sentiment, language, COUNT(*) as review_count 
    FROM amazon_reviews_enriched
    GROUP BY sentiment, language
    ORDER BY sentiment, language 
    ```
    Results show distribution of review by sentiment, for each language
    ```
    sentiment	language	review_count
    MIXED	    en	        179
    NEGATIVE	en	        392
    NEGATIVE	fr	        1
    NEUTRAL	    en	        1159
    NEUTRAL	    fr	        1
    NEUTRAL	    it	        2
    NEUTRAL	    pt	        2
    POSITIVE	en	        3301
    POSITIVE	es	        4
    POSITIVE	it	        1
    ```

7. Translate review headlines to English from all source languages
    ```
    ALTER TABLE amazon_reviews_enriched ADD COLUMN review_headline_en VARCHAR;
    UPDATE amazon_reviews_enriched 
    SET review_headline_en = translate_text(review_headline, language, 'en', 'null');
    ```
    Take a look at the results:
    ```
    SELECT language, review_headline, review_headline_en
    FROM amazon_reviews_enriched
    WHERE language <> 'en'
    LIMIT 5
    
    lang    review_headline                                               review_headline_en
    pt	    A Alma Portuguesa	                                          The Portuguese Soul
    es    	Magistral !!!	                                              Masterful!!!
    fr	    On Oracle 7.1--Look for the 2nd Edition (ISBN 0782118402)     On Oracle 7.1—Look for the 2nd Edition (ISBN 0782118402)
    es	    In spanish (gran descripcion de la transformacion de la era)  In spanish (great description of the transformation of the era)
    es	    ALUCINANTE.	                                                  MIND-BLOWING.
    ```

8. Look for PII
    
    ```
    ALTER TABLE amazon_reviews_enriched ADD COLUMN pii VARCHAR;
    UPDATE amazon_reviews_enriched 
    SET pii = detect_pii_entities(review_headline_en, 'en') 
    
    --- Example, look for ADDRESS in the product reviews
    SELECT review_headline_en, pii FROM amazon_reviews_enriched
    WHERE pii LIKE '%ADDRESS%'
    
    review_headline	                                              pii
    Wistfully Abbey's best desert writing outside USA	            [["NAME","Abbey"],["ADDRESS","USA"]]
    Highly recommended, richly atmospheric mystery set in Venice	[["ADDRESS","Venice"]]
    An invaluable resource for keeping in touch across the USA.	  [["ADDRESS","USA"]]
    Excellent detective trilogy set in 1940's Germany	            [["DATE_TIME","1940"],["ADDRESS","Germany"]]
    &quot;Carrie&quot; meets &quot;Beverly Hills 90210&quot;	    [["NAME","Carrie"],["ADDRESS","Beverly Hills 90210"]]
    Comprehensive, if dated, look at the town of Uniontown, MD.	  [["ADDRESS","Uniontown, MD"]]
    Wouk meets Uris meets DeMille in Vietnam.	                    [["NAME","Wouk"],["NAME","Uris"],["NAME","DeMille"],["ADDRESS","Vietnam"]]
    Good look at medical training in US	                          [["ADDRESS","US"]]
    An excellent Story of Swedish Immigrants in Boston	          [["ADDRESS","Boston"]]
    ```
    *Notes:  
    (1) Comprehend's detectPiiEntities API currently supports English only, however we can use the English translation from previous step to allow us to detect PII in all reviews.  


#### Use JSON to extract fields from the Comprehend JSON full response

```
CREATE TABLE text_with_languages (text VARCHAR, dominant_languages VARCHAR)
INSERT INTO text_with_languages VALUES
    ('It is raining in Seattle, mais il fait beau à orlando', NULL),
    ('It is raining in Seattle', NULL),
    ('Esta lloviendo en seattle', NULL);
UPDATE text_with_languages SET dominant_languages = detect_dominant_language_all(text);


-- input text with full results from Comprehend
SELECT text, dominant_languages FROM text_with_languages ;

text	                                                  dominant_languages
It is raining in Seattle, mais il fait beau à orlando	  [{"languageCode":"en","score":0.5149423},{"languageCode":"fr","score":0.44717678}]
It is raining in Seattle	                              [{"languageCode":"en","score":0.99071777}]
Esta lloviendo en seattle	                              [{"languageCode":"es","score":0.98879844}]


-- input text, with only first detected language and score
SELECT text, json_extract_array_element_text(dominant_languages, 0) AS language_and_score FROM text_with_languages

text	                                                  language_and_score
It is raining in Seattle, mais il fait beau à orlando	  {"languageCode":"en","score":0.5149423}
It is raining in Seattle      	                          {"languageCode":"en","score":0.99071777}
Esta lloviendo en seattle	                              {"languageCode":"es","score":0.98879844}


-- input text, with dominant (1st) detected language and score selected as separate columns
SELECT 
    text, 
    json_extract_path_text(json_extract_array_element_text(dominant_languages, 0), 'languageCode') AS language, 
    json_extract_path_text(json_extract_array_element_text(dominant_languages, 0), 'score') AS language 
FROM text_with_languages
    
text	                                                   language	score
It is raining in Seattle, mais il fait beau à orlando	  "en"	    0.5149423
It is raining in Seattle	                              "en"	    0.99071777
Esta lloviendo en seattle	                              "es"	    0.98879844

```


## License

This project is licensed under the MIT-0 License.
