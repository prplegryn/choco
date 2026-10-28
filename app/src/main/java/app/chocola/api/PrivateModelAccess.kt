package app.chocola.api

import app.chocola.data.Provider
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Internal authenticated transport. No listening socket or client-visible API credentials. */
interface PrivateModelAccess {
    suspend fun request(provider: Provider, session: String, body: JSONObject): Request
    suspend fun imageRequest(provider: Provider, session: String, request: Request): Request = error("绘图账号接入未初始化")
    suspend fun response(request: Request, response: Response)
    suspend fun models(): List<String>
}
