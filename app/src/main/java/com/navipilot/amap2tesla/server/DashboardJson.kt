package com.navipilot.amap2tesla.server

import com.jixiexiaoge.drivingassist.data.DashboardSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Explicit keys keep the LAN display contract stable through release obfuscation. */
internal fun DashboardSnapshot.toWireJson(): String = JSONObject()
    .put("schemaVersion", schemaVersion)
    .put("panels", JSONArray().also { panelsJson ->
        panels.forEach { panel ->
            panelsJson.put(JSONObject()
                .put("id", panel.id).put("title", panel.title).put("source", panel.source)
                .put("status", panel.status).put("remainingMs", panel.remainingMs)
                .put("receiptKey", panel.receiptKey)
                .put("note", panel.note).put("tone", panel.tone)
                .put("metrics", JSONArray().also { metricsJson ->
                    panel.metrics.forEach { metric ->
                        metricsJson.put(JSONObject().put("id", metric.id).put("label", metric.label)
                            .put("value", metric.value).put("unit", metric.unit))
                    }
                }))
        }
    }).toString()
