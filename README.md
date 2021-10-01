# Amazon Redshift UDFs for text translation and analytics using Amazon Comprehend and Amazon Translate

This Redshift UDF Lambda provides (i) text translation between languages using Amazon Translate, (ii) text analytics including detection of language, sentiment, entities and PII using Amazon Comprehend, and (iii) redaction of detected entities and PII.

### Deploying the UDFs

#### Install pre-built UDFs from the AWS Serverless Application Repository (SAR)

Install the prebuilt Lambda function with the following steps:
1.	Navigate to the [RedshiftTextAnalyticsUDF](https://console.aws.amazon.com/lambda/home?region=us-east-1#/create/app?applicationId=arn:aws:serverlessrepo:us-east-1:777566285978:applications/RedshiftTextAnalyticsUDF) application in the AWS Serverless Application Repository.
2.	In the Application settings section, keep the settings at their defaults.
3.	Select I acknowledge that this app creates custom IAM roles.
4.	Choose Deploy.
5.	When the application has deployed, chose **CloudFormation stack** from the Application **Deployments** tab
6.	Choose the stack **Outputs**
7.	Select the IAM role ARN shown as the output labelled **RedshiftLambdaInvokeRole**, and associate it with your Redshift cluster using the Redshift console.
8.	Select the SQL code that is shown as the value of the output labelled **SQLScriptExternalFunction** - copy and paste this SQL into your Redshift SQL client or the Redshift console query editor.

Then try the query examples below, or examples of your own, using the UDF.

#### Build and Install UDF from source

1. From the project root dir, run `mvn clean install` if you haven't already.
3. From the project root dir, run  `./publish.sh <S3_BUCKET_NAME> redshift-udfs-textanalytics us-east-1` to publish the connector to your private AWS Serverless Application Repository. The S3_BUCKET in the command is where a copy of the connector's code will be stored for Serverless Application Repository to retrieve it. This will allow users with permission to do so, the ability to deploy instances of the connector via 1-Click form. Then navigate to [Serverless Application Repository](https://aws.amazon.com/serverless/serverlessrepo)
4. Deploy the lambda function from the serverless repo, or run `sam deploy --template-file packaged.yaml --stack-name RedshiftTextAnalyticsUDF --capabilities CAPABILITY_NAMED_IAM`
5. When the application has deployed, navigate to the **CloudFormation stack** named `RedshiftTextAnalyticsUDF` or `serverlessrepo-RedshiftTextAnalyticsUDF`
6.	Choose the stack **Outputs**
7.	Select the IAM role ARN shown as the output labelled **RedshiftLambdaInvokeRole**, and associate it with your Redshift cluster using the Redshift console.
8.	Select the SQL code that is shown as the value of the output labelled **SQLScriptExternalFunction** - copy and paste this SQL into your Redshift SQL client or the Redshift console query editor.  
  
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

#### f\_translate\_text(text_col VARCHAR, sourcelang VARCHAR, targetlang VARCHAR, terminologyname VARCHAR) RETURNS VARCHAR

Returns the translated string, in the target language specified. Source language can be explicitly specified, or use 'auto'
to detect source language automatically (the Translate service calls Comprehend behind the scenes to detect the source language when you use 'auto'). 
Specify a custom terminology name, or 'null' if you aren't using custom terminologies.
```
SELECT f_translate_text('It is a beautiful day in the neighborhood', 'auto', 'fr', 'null') as translated_text

translated_text
C'est une belle journée dans le quartier
```

### Detect Language

#### f\_detect\_dominant\_language(text_col VARCHAR) RETURNS VARCHAR

Returns string value with dominant language code:
```
SELECT f_detect_dominant_language('il fait beau à Orlando') as language

language
fr
```
#### f\_detect\_dominant\_language\_all(text_col VARCHAR) RETURNS VARCHAR

Returns the set of detected languages and scores as a JSON formatted string, which can be further analysed with Redshift's [JSON functions](https://docs.aws.amazon.com/redshift/latest/dg/json-functions.html).
```
SELECT f_detect_dominant_language_all('il fait beau à Orlando') as language_all

language_all
[{"languageCode":"fr","score":0.99807304}]
```

### Detect Sentiment

Input languages supported: en | es | fr | de | it | pt | ar | hi | ja | ko | zh | zh-TW (See [doc](https://docs.aws.amazon.com/comprehend/latest/dg/API_DetectSentiment.html#comprehend-DetectSentiment-request-LanguageCode) for latest)

#### f\_detect\_sentiment(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns string value with dominant sentiment:

```
SELECT f_detect_sentiment('Joe is very happy', 'en') as sentiment

sentiment
POSITIVE
```

#### f\_detect\_sentiment\_all(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns the dominant sentiment and all sentiment scores as a JSON formatted string, which can be further analysed with Redshift's [JSON functions](https://docs.aws.amazon.com/redshift/latest/dg/json-functions.html).

```
SELECT f_detect_sentiment_all('Joe is very happy', 'en') as sentiment_all

sentiment_all
{"sentiment":"POSITIVE","sentimentScore":{"positive":0.999519,"negative":7.407639E-5,"neutral":2.7478999E-4,"mixed":1.3210243E-4}}
```

### Detect and Redact Entities

Entity Types supported -- see [Entity types](https://docs.aws.amazon.com/comprehend/latest/dg/how-entities.html)
Input languages supported: en | es | fr | de | it | pt | ar | hi | ja | ko | zh | zh-TW (See [doc](https://docs.aws.amazon.com/comprehend/latest/dg/API_BatchDetectEntities.html#API_BatchDetectEntities_RequestSyntax) for latest)


#### f\_detect\_entities(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns JSON string value with list of PII types and values:

```
SELECT f_detect_entities('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en') as entities

entities
[["PERSON","Joe"],["LOCATION","Richmond VA"],["ORGANIZATION","Amazon"],["COMMERCIAL_ITEM","Echo Show"],["DATE","January 5th"]]
```

#### f\_detect\_entities\_all(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns the detected entity types, scores, values, and offsets as a JSON formatted string, which can be further analysed with Redshift's [JSON functions](https://docs.aws.amazon.com/redshift/latest/dg/json-functions.html).

```
SELECT f_detect_entities_all('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en') as entities_all

entities_all
[{"score":0.9956949,"type":"PERSON","text":"Joe","beginOffset":12,"endOffset":15},{"score":0.99672645,"type":"LOCATION","text":"Richmond VA","beginOffset":29,"endOffset":40},{"score":0.963684,"type":"ORGANIZATION","text":"Amazon","beginOffset":55,"endOffset":61},{"score":0.98822284,"type":"COMMERCIAL_ITEM","text":"Echo Show","beginOffset":62,"endOffset":71},{"score":0.998659,"type":"DATE","text":"January 5th","beginOffset":75,"endOffset":86}]
```

#### f\_redact\_entities(text_col VARCHAR, lang VARCHAR, type VARCHAR) RETURNS VARCHAR

Redacts specified entity values from the input string.
Use the `types` argument to specify a list of [PII types](https://docs.aws.amazon.com/comprehend/latest/dg/API_PiiEntity.html#comprehend-Type-PiiEntity-Type) to be redacted.  

```
-- redact PERSON
SELECT f_redact_entities('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en', 'PERSON') as entities_redacted

entities_redacted
His name is [PERSON], he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it

-- redact PERSON and DATE
SELECT f_redact_entities('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en', 'PERSON, DATE') as entities_redacted

entities_redacted
His name is [PERSON], he lives in Richmond VA, he bought an Amazon Echo Show on [DATE], and he loves it

-- redact ALL Entity types
SELECT f_redact_entities('His name is Joe, he lives in Richmond VA, he bought an Amazon Echo Show on January 5th, and he loves it', 'en', 'ALL') as entities_redacted

entities_redacted
His name is [PERSON], he lives in [LOCATION], he bought an [ORGANIZATION] [COMMERCIAL_ITEM] on [DATE], and he loves it
```


### Detect and Redact PII

PII Types supported -- see [PII types](https://docs.aws.amazon.com/comprehend/latest/dg/API_PiiEntity.html#comprehend-Type-PiiEntity-Type)
Input languages supported: 'en' (See [doc](https://docs.aws.amazon.com/comprehend/latest/dg/API_DetectPiiEntities.html#comprehend-DetectPiiEntities-request-LanguageCode) for latest)


#### f\_detect\_pii\_entities(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns JSON string value with list of PII types and values:

```
SELECT f_detect_pii_entities('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en') as pii

pii
[["NAME","Joe"],["USERNAME","joe123"],["ADDRESS","Richmond VA"]]
```

#### f\_detect\_pii\_entities\_all(text_col VARCHAR, lang VARCHAR) RETURNS VARCHAR

Returns the detected PII types, scores, and offsets as a JSON formatted string, which can be further analysed with Redshift's [JSON functions](https://docs.aws.amazon.com/redshift/latest/dg/json-functions.html).

```
SELECT f_detect_pii_entities_all('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en') as pii_all

pii_all
[{"score":0.999894,"type":"NAME","beginOffset":12,"endOffset":15},{"score":0.99996245,"type":"USERNAME","beginOffset":33,"endOffset":39},{"score":0.9999982,"type":"ADDRESS","beginOffset":56,"endOffset":67}]
```

#### f\_redact\_pii\_entities(text_col VARCHAR, lang VARCHAR, type VARCHAR) RETURNS VARCHAR

Redacts specified entity values from the input string.
Use the `types` argument to specify a list of [PII types](https://docs.aws.amazon.com/comprehend/latest/dg/API_PiiEntity.html#comprehend-Type-PiiEntity-Type) to be redacted.  

```
-- redact name
SELECT f_redact_pii_entities('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en', 'NAME') as pii_redacted

pii_redacted
His name is [NAME], his username is joe123 and he lives in Richmond VA

-- redact NAME and ADDRESS
SELECT f_redact_pii_entities('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en', 'NAME,ADDRESS') as pii_redacted

pii_redacted
His name is [NAME], his username is joe123 and he lives in [ADDRESS]

-- redact ALL PII types
SELECT f_redact_pii_entities('His name is Joe, his username is joe123 and he lives in Richmond VA', 'en', 'ALL') as pii_redacted

pii_redacted
His name is [NAME], his username is [USERNAME] and he lives in [ADDRESS]
```


## Use case tutorial

See the AWS blog post: [Translate and analyze text using SQL functions with Amazon Redshift, Amazon Translate, and Amazon Comprehend](http://www.amazon.com/redshift-textanalyticsudf)

The SQL functions described here are also available for Amazon Athena. For more information, see [Translate, redact, and analyze text using SQL functions with Amazon Athena, Amazon Translate, and Amazon Comprehend](http://www.amazon.com/athena-textanalyticsudf).

## License

This project is licensed under the MIT-0 License.
