/* SPDX-License-Identifier: GPL-3.0-only
 * Based on Tasker Plugin Library's documented event sample, Joao Dias.
 */
package dev.uwbpulse.monitor.automation

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.joaomgcd.taskerpluginlibrary.TaskerPluginConstants
import com.joaomgcd.taskerpluginlibrary.condition.TaskerPluginRunnerConditionEvent
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.*
import com.joaomgcd.taskerpluginlibrary.output.*
import com.joaomgcd.taskerpluginlibrary.runner.*
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.domain.MonitorEventType
import net.dinglisch.android.tasker.TaskerPlugin

@TaskerInputRoot
class EventFilter @JvmOverloads constructor(@field:TaskerInputField("eventFilter") var event: String = "ALL")

@TaskerInputRoot
@TaskerOutputObject
class EventData @JvmOverloads constructor(
    @field:TaskerInputField("event") @get:TaskerOutputVariable("uwb_event",labelResIdName="tasker_event_label") val event: String = "",
    @field:TaskerInputField("distance") @get:TaskerOutputVariable("uwb_distance",labelResIdName="tasker_distance_label") val distance: String = "",
    @field:TaskerInputField("threshold") @get:TaskerOutputVariable("uwb_threshold",labelResIdName="tasker_threshold_label") val threshold: String = "",
    @field:TaskerInputField("timestamp") @get:TaskerOutputVariable("uwb_timestamp",labelResIdName="tasker_timestamp_label") val timestamp: String = "",
    @field:TaskerInputField("tag") @get:TaskerOutputVariable("uwb_tag",labelResIdName="tasker_tag_label") val tag: String = "",
    @field:TaskerInputField("eventId") val eventId: String = "",
)
class TagEventRunner : TaskerPluginRunnerConditionEvent<EventFilter,EventData,EventData>() {
    override fun getSatisfiedCondition(context: Context,input: TaskerInput<EventFilter>,update: EventData?): TaskerPluginResultCondition<EventData> {
        if (update == null || (input.regular.event != "ALL" && input.regular.event != update.event)) return TaskerPluginResultConditionUnsatisfied()
        val app = context.applicationContext as MonitorApplication
        if (!app.events.isRecorded(update.eventId,update.event)) return TaskerPluginResultConditionUnsatisfied()
        return TaskerPluginResultConditionSatisfied(context,update)
    }
}
class TagEventHelper(config: TaskerPluginConfig<EventFilter>) : TaskerPluginConfigHelper<EventFilter,EventData,TagEventRunner>(config) {
    override val runnerClass = TagEventRunner::class.java
    override val inputClass = EventFilter::class.java
    override val outputClass = EventData::class.java
    override fun addToStringBlurb(input: TaskerInput<EventFilter>,blurbBuilder: StringBuilder) { blurbBuilder.append(input.regular.event) }
}
class TaskerEventActivity : Activity(),TaskerPluginConfig<EventFilter> {
    override val context get() = applicationContext
    private var selected = "ALL"
    private val helper by lazy { TagEventHelper(this) }
    override val inputForTasker get() = TaskerInput(EventFilter(selected))
    override fun assignFromInput(input: TaskerInput<EventFilter>) { selected = input.regular.event }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helper.onCreate()
        val choices = listOf("ALL") + MonitorEventType.entries.map { it.name }
        AlertDialog.Builder(this).setTitle("UWB Pulse 이벤트")
            .setSingleChoiceItems(choices.toTypedArray(),choices.indexOf(selected).coerceAtLeast(0)) { _,which -> selected = choices[which] }
            .setPositiveButton("저장") { _,_ -> helper.finishForTasker() }
            .setNegativeButton("취소") { _,_ -> setResult(RESULT_CANCELED);finish() }
            .setOnCancelListener { finish() }.show()
    }
}
object TaskerEvents {
    fun send(context: Context,event: MonitorEvent) {
        val update = EventData(event.type.name,event.meters?.toString().orEmpty(),event.threshold.toString(),event.timestamp,event.tagAlias,event.id)
        val bundle = TaskerInputInfos.fromInput(context,update).bundle
        bundle.putString(TaskerPluginConstants.EXTRA_CONDITION_UPDATE_CLASS,EventData::class.java.name)
        val request = Intent(TaskerPluginConstants.ACTION_REQUEST_QUERY).setPackage("net.dinglisch.android.taskerm")
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra(TaskerPluginConstants.EXTRA_ACTIVITY,TaskerEventActivity::class.java.name)
        TaskerPlugin.Event.addPassThroughMessageID(request)
        TaskerPlugin.Event.addPassThroughData(request,bundle)
        context.sendBroadcast(request) // Explicit package: no telemetry broadcast to unrelated apps.
    }
}
