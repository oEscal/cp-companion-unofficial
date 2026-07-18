package pt.cpcompanion.automation

import android.content.Context
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.domain.TripResolutionException
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketActivationMethod
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.network.ApiConfigurationException
import pt.cpcompanion.network.ApiCooldownException
import pt.cpcompanion.network.ApiHttpException

object TicketValidationCoordinator {
    data class Outcome(
        val ticket: Ticket,
        val validated: Boolean,
        val shouldRetry: Boolean,
        val message: String,
    )

    suspend fun validateAndSchedule(context: Context, source: Ticket): Outcome {
        val app = context.applicationContext as TrainTrackerApplication
        val currentSource = app.container.stores.ticket(source.id) ?: source
        return try {
            val validated = TicketAutomationValidator.validate(context, currentSource)
            TicketActivationScheduler.schedule(context, validated, force = true)
            Outcome(
                ticket = validated,
                validated = true,
                shouldRetry = false,
                message = "Ticket validated; automatic tracking is scheduled",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val classification = classify(error)
            val latest = app.container.stores.ticket(currentSource.id) ?: currentSource
            val failures = latest.validationFailureCount + 1
            val exhausted = classification.maxAttempts?.let { failures >= it } == true
            val permanent = !classification.transient || exhausted
            val message = when {
                !classification.transient -> classification.message
                exhausted -> "Validation failed repeatedly: ${classification.message}"
                else -> "Validation will retry automatically: ${classification.message}"
            }
            val updated = app.container.stores.updateTicket(currentSource.id) { current ->
                current.copy(
                    automaticTrackingEnabled = if (permanent) false else current.automaticTrackingEnabled,
                    automationState = if (permanent) TicketAutomationState.FAILED else TicketAutomationState.NEEDS_VALIDATION,
                    activationEpochMillis = if (permanent) null else current.activationEpochMillis,
                    activationMethod = if (permanent) TicketActivationMethod.NONE else current.activationMethod,
                    schedulingFingerprint = if (permanent) null else current.schedulingFingerprint,
                    automationMessage = if (permanent) "$message. Re-enable automatic tracking after correcting the ticket." else message,
                    lastAutomationAttemptEpochMillis = System.currentTimeMillis(),
                    validationFailureCount = failures,
                )
            } ?: currentSource
            if (permanent) TicketActivationScheduler.cancel(context, currentSource.id)
            Outcome(updated, validated = false, shouldRetry = !permanent, message = message)
        }
    }

    private fun classify(error: Throwable): FailureClassification = when (error) {
        is ApiCooldownException -> FailureClassification(true, "CP is rate limiting requests")
        is ApiHttpException -> when {
            error.statusCode == 429 -> FailureClassification(true, "CP is rate limiting requests")
            error.statusCode in setOf(401, 403) -> FailureClassification(true, "CP authorization is temporarily unavailable")
            error.statusCode == 404 -> FailureClassification(false, "CP could not find this train on the selected service date")
            error.statusCode in 400..499 -> FailureClassification(false, "The ticket is not accepted by the CP trip service")
            else -> FailureClassification(true, "CP is temporarily unavailable")
        }
        is TripResolutionException -> FailureClassification(false, error.message ?: "Ticket stations do not match the train trip")
        is DateTimeParseException -> FailureClassification(false, "The ticket service date is invalid")
        is ApiConfigurationException -> FailureClassification(true, error.message ?: "CP configuration is unavailable")
        is SocketTimeoutException -> FailureClassification(true, "CP validation timed out")
        is UnknownHostException -> FailureClassification(true, "Network unavailable")
        is SerializationException -> FailureClassification(
            transient = true,
            message = "CP returned an unreadable response",
            maxAttempts = MAX_TRANSIENT_VALIDATION_FAILURES,
        )
        is IOException -> FailureClassification(true, error.message ?: "Network unavailable")
        is IllegalArgumentException -> FailureClassification(false, error.message ?: "Invalid ticket data")
        else -> FailureClassification(
            transient = true,
            message = error.message ?: error.javaClass.simpleName,
            maxAttempts = MAX_TRANSIENT_VALIDATION_FAILURES,
        )
    }

    private data class FailureClassification(
        val transient: Boolean,
        val message: String,
        val maxAttempts: Int? = null,
    )

    private const val MAX_TRANSIENT_VALIDATION_FAILURES = 6
}
