/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.tom.rv2ide.artificial.agents.openrouter

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.parser.SnippetParser
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.preferences.internal.prefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class OpenRouter : AIAgent {

    private var apiKey: String? = null
    private val writingRules = WritingRules.Instructions()
    private var projectTreeResult: ProjectTreeResult? = null
    private var fileWriter: AIFileWriter? = null
    private val conversationHistory = mutableListOf<OpenRouterConversationMessage>()
    private val modificationHistory = mutableListOf<ModificationAttempt>()
    private var currentAttemptCount = 0
    private val maxRetryAttempts = 3
    private var agents: Agents? = null
    private var selectedModel: String = "google/gemini-2.0-flash-exp:free"
    override val providerId = "openrouter"
    override val providerName = "OpenRouter"

    companion object {
        private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val HTTP_REFERER = "https://github.com/zwyft/android-code-studio-openrouter"
        private const val APP_TITLE = "Android Code Studio"

        fun registerAgent() {
            AIAgentRegistry.register("openrouter", object : AIAgentRegistry.AgentFactory {
                override fun create(context: Context): AIAgent = OpenRouter()

                override fun hasValidApiKey(): Boolean {
                    val key = ApiKey.getOpenRouterApiKey()
                    android.util.Log.d("OpenRouter", "hasValidApiKey: length=${key.length}")
                    return key.isNotEmpty()
                }

                override fun getApiKey(): String? {
                    val key = ApiKey.getOpenRouterApiKey()
                    return key.ifEmpty { null }
                }
            })
        }
    }

    override fun initialize(apiKey: String, context: Context) {
        this.apiKey = apiKey
        agents = Agents(context)
        var model = agents?.getAgent() ?: "google/gemini-2.0-flash-exp:free"

        // Accept any OpenRouter model (provider/model format) or fall back to default
        if (!isValidOpenRouterModel(model)) {
            model = "google/gemini-2.0-flash-exp:free"
            agents?.setAgent(model)
            agents?.setProvider("openrouter")
        }

        selectedModel = model
    }

    override fun reinitializeWithNewModel(apiKey: String, context: Context) {
        initialize(apiKey, context)
    }

    override fun setContext(context: Context) {
        fileWriter = AIFileWriter(context)
    }

    override fun setProjectData(projectTreeResult: ProjectTreeResult) {
        this.projectTreeResult = projectTreeResult
    }

    override fun clearConversation() {
        conversationHistory.clear()
        modificationHistory.clear()
        currentAttemptCount = 0
    }

    override fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean) {
        modificationHistory.add(
            ModificationAttempt(
                timestamp = System.currentTimeMillis(),
                filePath = filePath,
                previousContent = oldContent,
                newContent = newContent,
                attemptNumber = currentAttemptCount,
                success = success
            )
        )
    }

    override fun undoLastModification(): Boolean {
        if (modificationHistory.isEmpty()) return false
        val lastMod = modificationHistory.lastOrNull { it.success } ?: return false
        if (lastMod.previousContent != null) {
            val result = writeFile(lastMod.filePath, lastMod.previousContent)
            if (result is FileWriteResult.Success) {
                modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
                return true
            }
        } else {
            return try {
                File(lastMod.filePath).delete()
                modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
                true
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    override fun getModificationHistory(): List<ModificationAttempt> = modificationHistory.toList()

    override fun resetAttemptCount() { currentAttemptCount = 0 }
    override fun incrementAttemptCount() { currentAttemptCount++ }
    override fun getCurrentAttemptCount(): Int = currentAttemptCount
    override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

    private fun isValidOpenRouterModel(model: String): Boolean {
        // OpenRouter models follow provider/model-name format, or are in our known list
        return model.contains("/") || model in (agents?.getModelsForProvider("openrouter") ?: emptyArray())
    }

    private fun isUserRequestingCorrection(message: String): Boolean {
        val keywords = listOf(
            "wrong", "not what", "mistake", "error", "incorrect",
            "that's not", "not right", "fix", "undo", "revert",
            "different", "try again", "not working"
        )
        return keywords.any { message.lowercase().contains(it) }
    }

    override suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val key = apiKey ?: return@withContext Result.failure(
                IllegalStateException("OpenRouter service not initialized")
            )

            val fileContents = readRelevantFiles()
            val needsCorrection = isUserRequestingCorrection(prompt)

            val fullPrompt = buildString {
                append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
                if (projectTreeResult != null) {
                    append(projectTreeResult!!.tree)
                    append("\n\n")
                    append("CRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths.\n")
                    append("CRITICAL: Look at the actual paths above and use those EXACT paths.\n\n")
                }

                if (fileContents.isNotEmpty()) {
                    append("=== CURRENT FILES CONTENT ===\n")
                    fileContents.forEach { (path, content) ->
                        append("FILE: $path\n")
                        append("CONTENT:\n")
                        append(content)
                        append("\n\n")
                    }
                }

                if (context != null) {
                    append("=== ADDITIONAL CONTEXT ===\n")
                    append(context)
                    append("\n\n")
                }

                if (conversationHistory.isNotEmpty()) {
                    append("=== CONVERSATION HISTORY ===\n")
                    conversationHistory.forEach { msg ->
                        append("${msg.role.uppercase()}: ${msg.content}\n\n")
                    }
                }

                if (needsCorrection && modificationHistory.isNotEmpty()) {
                    append("=== CORRECTION REQUIRED ===\n")
                    append("The user indicated the previous modification was WRONG.\n")
                    append("Previous failed attempts:\n")
                    modificationHistory.takeLast(3).forEach { attempt ->
                        append("Attempt ${attempt.attemptNumber}: ${attempt.filePath}\n")
                        append("Result: ${if (attempt.success) "Applied but user rejected" else "Failed"}\n\n")
                    }
                    append("You MUST try a DIFFERENT approach.\n\n")
                }

                if (currentAttemptCount > 0) {
                    append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n")
                    append("This is retry attempt number $currentAttemptCount.\n")
                    append("Think carefully and provide a different solution.\n\n")
                }

                append("=== USER REQUEST ===\n")
                append(prompt)
            }

            val response = callOpenRouterAPI(key, fullPrompt)

            if (response.isBlank()) {
                return@withContext Result.failure(Exception("Empty response from OpenRouter"))
            }

            conversationHistory.add(OpenRouterConversationMessage("user", prompt))
            conversationHistory.add(OpenRouterConversationMessage("assistant", response))

            if (conversationHistory.size > 20) {
                conversationHistory.removeAt(0)
                conversationHistory.removeAt(0)
            }

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun callOpenRouterAPI(apiKey: String, prompt: String): String {
        android.util.Log.d("OpenRouter", "Starting API call, model=$selectedModel")

        // Append :online suffix if web search is enabled and model doesn't already have it
        val effectiveModel = if (prefManager.getBoolean("openrouter_web_search_enabled", false)
            && !selectedModel.contains(":online")
            && !selectedModel.contains(":free")
        ) {
            "$selectedModel:online"
        } else {
            selectedModel
        }

        // Free tier models need longer timeouts
        val timeoutMs = if (effectiveModel.contains(":free")) 120_000 else 60_000

        val url = URL(BASE_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("HTTP-Referer", HTTP_REFERER)
            connection.setRequestProperty("X-Title", APP_TITLE)
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = timeoutMs

            val messages = JSONArray()

            val systemMessage = JSONObject()
            systemMessage.put("role", "system")
            systemMessage.put("content", writingRules.useThis())
            messages.put(systemMessage)

            val userMessage = JSONObject()
            userMessage.put("role", "user")
            userMessage.put("content", prompt)
            messages.put(userMessage)

            val requestBody = JSONObject()
            requestBody.put("model", effectiveModel)
            requestBody.put("messages", messages)
            requestBody.put("temperature", 0.7)
            requestBody.put("max_tokens", 4096)

            android.util.Log.d("OpenRouter", "Effective model: $effectiveModel")

            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            android.util.Log.d("OpenRouter", "Response code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                android.util.Log.e("OpenRouter", "Error response: $errorStream")

                try {
                    val errorJson = JSONObject(errorStream)
                    val errorObj = errorJson.optJSONObject("error")
                    val errorMessage = errorObj?.optString("message") ?: errorStream
                    val errorCode = errorObj?.optString("code") ?: ""

                    when {
                        responseCode == 429 || errorCode.contains("rate_limit") ->
                            throw RateLimitException("OpenRouter rate limit exceeded: $errorMessage")
                        errorMessage.contains("quota") || errorMessage.contains("billing") || errorMessage.contains("credits") ->
                            throw QuotaExceededException("OpenRouter quota exceeded: $errorMessage")
                        responseCode == 401 || errorCode.contains("invalid_api_key") ->
                            throw InvalidApiKeyException("Invalid OpenRouter API key: $errorMessage")
                        else ->
                            throw Exception("OpenRouter API error ($responseCode): $errorMessage")
                    }
                } catch (e: RateLimitException) { throw e
                } catch (e: QuotaExceededException) { throw e
                } catch (e: InvalidApiKeyException) { throw e
                } catch (e: Exception) {
                    throw Exception("OpenRouter API error ($responseCode): $errorStream")
                }
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            android.util.Log.d("OpenRouter", "Response received, length=${responseBody.length}")

            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() > 0) {
                val message = choices.getJSONObject(0).getJSONObject("message")
                return message.getString("content")
            }

            throw Exception("No response content from OpenRouter API")
        } catch (e: RateLimitException) {
            android.util.Log.e("OpenRouter", "Rate limit", e); throw e
        } catch (e: QuotaExceededException) {
            android.util.Log.e("OpenRouter", "Quota exceeded", e); throw e
        } catch (e: InvalidApiKeyException) {
            android.util.Log.e("OpenRouter", "Invalid key", e); throw e
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.e("OpenRouter", "Timeout", e)
            throw Exception("OpenRouter request timeout (model may be slow on free tier): ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            android.util.Log.e("OpenRouter", "Network error", e)
            throw Exception("Network error - cannot reach OpenRouter: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("OpenRouter", "General error", e); throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun readRelevantFiles(): Map<String, String> {
        val filesContent = mutableMapOf<String, String>()
        val tree = projectTreeResult?.tree ?: return filesContent

        tree.lines().filter { it.isNotBlank() }.forEach { filePath ->
            val trimmedPath = filePath.trim()
            val file = File(trimmedPath)
            if (file.isFile &&
                (trimmedPath.endsWith(".kt") ||
                    trimmedPath.endsWith(".java") ||
                    trimmedPath.endsWith(".xml") ||
                    trimmedPath.endsWith(".gradle") ||
                    trimmedPath.endsWith(".gradle.kts")) &&
                !trimmedPath.contains("/build/") &&
                !trimmedPath.contains("/.gradle/")
            ) {
                try {
                    filesContent[trimmedPath] = file.readText()
                } catch (e: Exception) {
                    // skip unreadable files
                }
            }
        }
        return filesContent
    }

    override fun writeFile(filePath: String, content: String): FileWriteResult {
        val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
        return writer.writeFile(filePath, content, createBackup = true)
    }

    override fun isInitialized(): Boolean = apiKey != null
}

data class OpenRouterConversationMessage(
    val role: String,
    val content: String
)
