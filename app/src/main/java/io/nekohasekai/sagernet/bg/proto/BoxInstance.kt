package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteriaConfig
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.forEach
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.plus
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.plugin.NekoPluginManager
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.neko.NekoJSInterface
import moe.matsuri.nb4a.proxy.neko.updateAllConfig
import org.json.JSONObject
import java.io.File

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildConfig(profile)
    }

    protected open suspend fun loadConfig() {
        NekoJSInterface.Default.destroyAllJsi()
        box = Libcore.newSingBoxInstance(config.config, false)
    }

    open suspend fun init() {
        buildConfig()
        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }

                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMieruConfig(port)
                    }

                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port)
                    }

                    is HysteriaBean -> {
                        when (bean.protocolVersion) {
                            1 -> initPlugin("hysteria-plugin")
                            2 -> initPlugin("hysteria2-plugin")
                        }
                        pluginConfigs[port] = profile.type to bean.buildHysteriaConfig(port) {
                            File(
                                app.cacheDir, "hysteria_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }

                    is NekoBean -> {
                        // check if plugin binary can be loaded
                        initPlugin(bean.plgId)

                        // build config and check if succeed
                        bean.updateAllConfig(port)
                        if (bean.allConfig == null) {
                            throw NekoPluginManager.PluginInternalException(bean.protocolId)
                        }
                    }
                }
            }
        }
        loadConfig()
    }

    override fun launch() {
        // TODO move, this is not box
        val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")
        cacheDir.mkdirs()

        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val needChain = index != chain.size - 1
                val (profileType, config) = pluginConfigs[port] ?: (0 to "")

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }

                    bean is TrojanGoBean -> {
                        val configFile = File(
                            cacheDir, "trojan_go_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }

                    bean is MieruBean -> {
                        val configFile = File(
                            cacheDir, "mieru_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf(
                            "MIERU_CONFIG_JSON_FILE" to configFile.absolutePath,
                            "MIERU_PROTECT_PATH" to "protect_path",
                        )

                        val commands = mutableListOf(
                            initPlugin("mieru-plugin").path, "run",
                        )

                        processes.start(commands, envMap)
                    }

                    bean is NaiveBean -> {
                        val configFile = File(
                            cacheDir, "naive_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )

                        processes.start(commands, envMap)
                    }

                    bean is HysteriaBean -> {
                        val configFile = File(
                            cacheDir, "hysteria_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf("HYSTERIA_DISABLE_UPDATE_CHECK" to "1")

                        val commands = if (bean.protocolVersion == 1) {
                            mutableListOf(
                                initPlugin("hysteria-plugin").path,
                                "--no-check",
                                "--config",
                                configFile.absolutePath,
                                "--log-level",
                                if (DataStore.logLevel > 0) "trace" else "warn",
                                "client"
                            )
                        } else {
                            mutableListOf(
                                initPlugin("hysteria2-plugin").path, "client",
                                "--config", configFile.absolutePath,
                                "--log-level", if (DataStore.logLevel > 0) "warn" else "error"
                            )
                        }

                        if (bean.protocolVersion == 1 && bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands, envMap)
                    }

                    bean is NekoBean -> {
                        // config built from JS
                        val nekoRunConfigs = bean.allConfig.optJSONArray("nekoRunConfigs")
                        val configs = mutableMapOf<String, String>()

                        nekoRunConfigs?.forEach { _, any ->
                            any as JSONObject

                            val name = any.getString("name")
                            val configFile = File(cacheDir, name)
                            configFile.parentFile?.mkdirs()
                            val content = any.getString("content")
                            configFile.writeText(content)

                            cacheFiles.add(configFile)
                            configs[name] = configFile.absolutePath

                            Logs.d(name + "\n\n" + content)
                        }

                        val nekoCommands = bean.allConfig.getJSONArray("nekoCommands")
                        val commands = mutableListOf<String>()

                        nekoCommands.forEach { _, any ->
                            if (any is String) {
                                if (configs.containsKey(any)) {
                                    commands.add(configs[any]!!)
                                } else if (any == "%exe%") {
                                    commands.add(initPlugin(bean.plgId).path)
                                } else {
                                    commands.add(any)
                                }
                            }
                        }

                        processes.start(commands)
                    }
                }
            }
        }

        box.start()
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll { it.delete(); true }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (::box.isInitialized) {
            try {
                box.close()
            } catch (e : Exception) {
                Logs.w(e)
                Libcore.kill()
            }
        }
    }

}
