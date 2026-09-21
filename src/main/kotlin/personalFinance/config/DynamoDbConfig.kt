package personalFinance.config

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DynamoDbConfig(
    @Value("\${aws.region}") private val awsRegion: String,
    @Value("\${aws.url}") private val url: String,
    @Value("\${aws.accessKeyId}") private val accessKeyId: String,
    @Value("\${aws.secretAccessKey}") private val secretAccessKey: String,
) {
    @Bean
    fun dynamoDbClient(): DynamoDbClient = DynamoDbClient {
        region = awsRegion
        endpointUrl = Url.parse(url)
        credentialsProvider = StaticCredentialsProvider(
            Credentials(accessKeyId = accessKeyId, secretAccessKey = secretAccessKey)
        )
    }
}
