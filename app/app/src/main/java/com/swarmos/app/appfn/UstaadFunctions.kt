package com.swarmos.app.appfn

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionLimitExceededException
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.swarmos.app.hands.HandsHub
import com.swarmos.app.hands.android.HandsRuntime
import com.swarmos.app.hands.android.HandsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The tools of Ustaad's Hands for the phone's own assistant, through Android App Functions (Android 16 and later; the system lets only an
 * assistant it allows call them). The functions below are the whole surface; the library generates the service `UstaadAppFunctionService`
 * and the description of each function from the comments. Every decision is in [AppFnPolicy]; this class only hands the call to it, off
 * the main thread, and turns a refusal into the library's exception class.
 *
 * Nothing here can answer Ustaad's questions to the owner, change a switch, or start a task the owner has not allowed (the start switch).
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(serviceName = "UstaadAppFunctionService", appFunctionXmlFileName = "ustaad_app_function_service")
abstract class UstaadFunctions : AppFunctionService() {
    /**
     * Gives Ustaad a task to carry out on the owner's phone, such as ordering groceries or setting a timer, and returns the task id at once.
     * Use it when the owner asks you to do something in their phone's apps. Ustaad asks the owner before anything that cannot be undone, and
     * it may wait for the owner to tap Run first.
     * The reply starts with the task id on its own line, then says the state in plain words. Poll taskStatus with that id.
     *
     * @param task What the owner wants done, in plain words, at most 500 characters.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun startTask(task: String): String = guarded { it.startTask(task) }

    /**
     * Tells the state of a task that startTask returned an id for. Use it to follow a task, and to read its result when it is done.
     * The reply gives the state, the plan with a status per step and, when the task has ended, its result.
     *
     * @param taskId The task id from the first line of the startTask reply.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun taskStatus(taskId: String): String = guarded { it.taskStatus(taskId) }

    /**
     * Stops a task that startTask started, or discards it if the owner has not tapped Run yet. Use it when the owner says to stop or cancel.
     *
     * @param taskId The task id from the first line of the startTask reply.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun stopTask(taskId: String): String = guarded { it.stopTask(taskId) }

    /**
     * Asks one of Ustaad's knowledge experts a question, for example about cooking or money, and returns its answer. Use it for general
     * knowledge; call listExperts first to see which domains exist. It does not touch the owner's apps or data.
     *
     * @param domain The expert's domain, exactly as listExperts names it.
     * @param question The question in plain words, at most 500 characters.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun askExpert(domain: String, question: String): String = guarded { it.askExpert(domain, question) }

    /**
     * Lists the knowledge experts Ustaad can ask, one per line, each with the domain name and what it is good for. Use it before askExpert.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listExperts(): String = guarded { it.listExperts() }

    /**
     * Searches the owner's own files and notes that Ustaad has indexed on this phone and returns the best matching passages with their
     * sources. Use it when the owner asks about something in their documents. It only works if the owner has allowed it in Ustaad.
     *
     * @param query What to look for, in plain words, at most 500 characters.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun searchMyData(query: String): String = guarded { it.searchMyData(query) }

    private suspend fun guarded(call: suspend (AppFnPolicy) -> String): String = withContext(Dispatchers.IO) {
        try {
            call(policy())
        } catch (e: AppFnRefusal) {
            throw e.toLibrary()
        }
    }

    private fun policy(): AppFnPolicy {
        val app = applicationContext
        return AppFnPolicy(
            hands = { withTimeoutOrNull(START_WAIT_MS) { while (HandsHub.hands == null) delay(100); HandsHub.hands } },
            desk = { HandsRuntime.expertDesk },
            experts = { HandsRuntime.expertCards },
            localData = HandsRuntime::searchOwnDataLocalOnly,
            mayStart = { HandsSettings.assistantMayStart.value },
            maySearchData = { HandsSettings.assistantMaySearchData.value },
            launcher = HandsRuntime.launcher(app),
            tellOwner = { AppFnNotice.post(app, it) },
        )
    }

    private companion object {
        /** A function can arrive in a process that has only just started, before Hands is up. */
        const val START_WAIT_MS = 5_000L
    }
}

/** The library's exception class for each kind of refusal. */
internal fun AppFnRefusal.toLibrary(): AppFunctionException {
    val text = message.orEmpty()
    return when (kind) {
        AppFnRefusal.Kind.INVALID_ARGUMENT -> AppFunctionInvalidArgumentException(text)
        AppFnRefusal.Kind.NOT_FOUND -> AppFunctionElementNotFoundException(text)
        AppFnRefusal.Kind.DENIED -> AppFunctionDeniedException(text)
        AppFnRefusal.Kind.LIMIT -> AppFunctionLimitExceededException(text)
        AppFnRefusal.Kind.UNAVAILABLE -> AppFunctionAppUnknownException(text)
    }
}
