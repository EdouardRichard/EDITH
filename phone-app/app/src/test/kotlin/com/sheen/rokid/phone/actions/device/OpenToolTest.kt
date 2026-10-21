package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.phone.actions.intent.AppCatalog
import com.sheen.rokid.phone.actions.intent.AppEntry
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellFailure
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43 (section 4.11): `open` is a structured tool - ambiguity is a refusal whose `data.candidates` is the
 * payload (never a `CODE:payload` string), a URL is validated and single-quote-escaped before it
 * becomes a shell command, and a package launch resolves the launcher component first.
 */
class OpenToolTest {

    private val json = Json

    // ---- list ----

    @Test
    fun `list uses the launcher activity query and marks the 100-row truncation`() {
        val packages = (0 until 101).map { "com.example.app$it" }
        val shell = FakeShell { command ->
            when {
                command == LAUNCHER_QUERY -> ShellResult(
                    command,
                    0,
                    packages.joinToString("\n") { "$it/.MainActivity" } + "\n",
                    "",
                )
                else -> ShellResult(command, 1, "", "unexpected command: $command")
            }
        }
        val catalog = FakeCatalog(
            listOf(AppEntry("com.example.app0", "音乐", "android.intent.action.MAIN")),
        )

        val result = OpenTool(shell, catalog).execute("""{"list":true}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)["data"]!!.jsonObject
        assertEquals(100, data["apps"]!!.jsonArray.size)
        assertTrue(data["truncated"]!!.jsonPrimitive.boolean)
        val text = data["text"]!!.jsonPrimitive.content
        assertTrue("known app must use its label: $text", text.contains("音乐 (com.example.app0)"))
        assertTrue("unknown app falls back to its package", text.contains("com.example.app1 (com.example.app1)"))
        assertEquals(
            "the launcher activity query is the catalogue source; the third-party filter must be gone",
            listOf(LAUNCHER_QUERY),
            shell.commands,
        )
        // Task 5: list is a read, not a screen operation - execution facts only, never an invented observation/image.
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull("a list applies nothing", execution.applied)
        assertNull(execution.verified)
        assertNull("a list changes no screen", execution.changed)
        assertNull("list is not a screen operation", result.observation)
        assertTrue("list attaches no image", result.images.isEmpty())
        assertFalse("execution facts alone are persistent", result.nonPersistent)
    }

    @Test
    fun `list includes system apps from the launcher activity query`() {
        // R104 user report: `pm list packages -3` hid every system app. The launcher activity query returns the
        // package of each launchable component (one per line), including the system apps the wearer asked for.
        val shell = FakeShell { command ->
            when {
                command == LAUNCHER_QUERY -> ShellResult(
                    command,
                    0,
                    listOf(
                        "priority=0 preferredOrder=0",
                        "com.android.fileexplorer/.FileExplorer",
                        "com.miui.gallery/com.miui.gallery.app.GalleryActivity",
                        "com.android.camera/.Camera",
                        "com.android.settings/.Settings",
                        "com.miui.player/com.miui.player.ui.MusicActivity",
                        "com.example.notes/.MainActivity",
                    ).joinToString("\n") + "\n",
                    "",
                )
                else -> ShellResult(command, 1, "", "unexpected command: $command")
            }
        }
        val catalog = FakeCatalog(
            listOf(
                AppEntry("com.android.settings", "设置", "android.intent.action.MAIN"),
                AppEntry("com.miui.gallery", "相册", "android.intent.action.MAIN"),
            ),
        )

        val result = OpenTool(shell, catalog).execute("""{"list":true}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)["data"]!!.jsonObject
        assertEquals(
            "every launchable package is listed, system apps included",
            listOf(
                "com.android.fileexplorer",
                "com.miui.gallery",
                "com.android.camera",
                "com.android.settings",
                "com.miui.player",
                "com.example.notes",
            ),
            data["apps"]!!.jsonArray.map { it.jsonObject["packageName"]!!.jsonPrimitive.content },
        )
        val text = data["text"]!!.jsonPrimitive.content
        assertTrue("a catalog label is used for a system app: $text", text.contains("设置 (com.android.settings)"))
        assertTrue(
            "a missing label falls back to the package: $text",
            text.contains("com.android.fileexplorer (com.android.fileexplorer)"),
        )
        assertFalse(data["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `list reports the frozen catalog summary fields and a count-only log`() {
        val shell = FakeShell { command ->
            when (command) {
                LAUNCHER_QUERY -> ShellResult(
                    command,
                    0,
                    listOf(
                        "com.android.settings/.Settings",
                        "com.miui.player/com.miui.player.ui.MusicActivity",
                        "com.android.camera/.Camera",
                    ).joinToString("\n") + "\n",
                    "",
                )
                else -> ShellResult(command, 1, "", "unexpected command: $command")
            }
        }
        val catalog = FakeCatalog(listOf(AppEntry("com.android.settings", "设置", "android.intent.action.MAIN")))
        val lines = mutableListOf<String>()
        val previous = com.sheen.rokid.phone.platform.P3Log.sink
        com.sheen.rokid.phone.platform.P3Log.sink = { lines += it }
        val result = try {
            OpenTool(shell, catalog).execute("""{"list":true}""")
        } finally {
            com.sheen.rokid.phone.platform.P3Log.sink = previous
        }

        val data = body(result)["data"]!!.jsonObject
        assertEquals("launcher", data["catalogVia"]!!.jsonPrimitive.content)
        assertEquals(3, data["catalogCount"]!!.jsonPrimitive.int)
        assertTrue(data["hasPlayer"]!!.jsonPrimitive.boolean)
        assertTrue(data["hasSettings"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("P3_TOOLCATALOG via=launcher count=3 hasPlayer=true hasSettings=true truncated=false"),
            lines,
        )
        assertFalse("the log must not contain app labels or package rows", lines.any { it.contains("com.miui.player") })
    }

    @Test
    fun `list falls back to every package when the launcher activity query is unusable`() {
        // R104: a failed/empty/unparseable query falls back to `pm list packages` (all packages), never to the -3 filter.
        val unusable = listOf(
            ShellResult("", 1, "", "Unknown command: query-activities"),
            ShellResult("", 0, "", ""),
            ShellResult("", 0, "No activities found\n", ""),
        )
        unusable.forEach { queryReply ->
            val shell = FakeShell { command ->
                when {
                    command == LAUNCHER_QUERY -> queryReply.copy(command = command)
                    command == "pm list packages" -> ShellResult(
                        command,
                        0,
                        "package:com.system.camera\npackage:com.example.notes\n",
                        "",
                    )
                    else -> ShellResult(command, 1, "", "unexpected command: $command")
                }
            }

            val result = OpenTool(shell, FakeCatalog()).execute("""{"list":true}""")

            assertEquals(
                "query reply ${queryReply.exitCode}/${queryReply.stdout}",
                ActionOutcome.Done,
                result.outcome,
            )
            assertEquals(
                listOf("com.system.camera", "com.example.notes"),
                body(result)["data"]!!.jsonObject["apps"]!!.jsonArray
                    .map { it.jsonObject["packageName"]!!.jsonPrimitive.content },
            )
            assertTrue(
                "the fallback is every package, never the third-party filter",
                shell.commands.contains("pm list packages"),
            )
            assertFalse(
                "query-activities must not silently fall back to pm list packages -3",
                shell.commands.any { it.contains("-3") },
            )
        }
    }

    @Test
    fun `list without a shell channel is CHANNEL_UNAVAILABLE with facts only`() {
        var captureCalls = 0
        var observeCalls = 0
        val result = OpenTool(
            shell = null,
            catalog = FakeCatalog(),
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Disabled
            },
            observe = {
                observeCalls++
                openObservation()
            },
        ).execute("""{"list":true}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
        assertEquals("list is not a screen operation, even when its channel is down", 0, captureCalls)
        assertEquals("list must not ask for an observation", 0, observeCalls)
        assertNull(result.observation)
        assertTrue(result.images.isEmpty())
        assertFalse(result.nonPersistent)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", execution.code)
        assertEquals(true, execution.recoverable)
        assertEquals(false, execution.accepted)
        assertNull("a list applies nothing", execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
    }

    // ---- resolution ----

    @Test
    fun `an ambiguous name refuses with structured candidates and the observed screen`() {
        val catalog = FakeCatalog(
            listOf(
                AppEntry("com.example.music1", "音乐", "android.intent.action.MAIN"),
                AppEntry("com.example.music2", "音乐", "android.intent.action.MAIN"),
            ),
        )
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }
        val image = openImage("artifact-ambiguous")
        var captureCalls = 0
        var observeCalls = 0

        val result = OpenTool(
            shell = shell,
            catalog = catalog,
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = {
                observeCalls++
                openObservation()
            },
        ).execute("""{"target":"音乐"}""")

        assertEquals("ambiguity stays a refusal", ActionOutcome.Refused, result.outcome)
        val body = body(result)
        assertEquals("APP_AMBIGUOUS", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(body["error"]!!.jsonObject["recoverable"]!!.jsonPrimitive.boolean)
        val candidates = body["data"]!!.jsonObject["candidates"]!!.jsonArray
        assertEquals(2, candidates.size)
        assertEquals("音乐", candidates[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("com.example.music1", candidates[0].jsonObject["packageName"]!!.jsonPrimitive.content)
        assertEquals("音乐", candidates[1].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("com.example.music2", candidates[1].jsonObject["packageName"]!!.jsonPrimitive.content)
        assertTrue("ambiguity must not launch anything", shell.commands.isEmpty())

        assertRefusalExecution(executionOf(result), "APP_AMBIGUOUS", recoverable = true)
        val observation = observationOf(result)
        assertEquals("com.android.launcher", observation.foregroundPackage)
        assertEquals(ToolVision.Ok, observation.vision)
        assertNull(observation.visionReason)
        assertEquals("the model can still see the screen that caused the ambiguity", 1, captureCalls)
        assertEquals(1, observeCalls)
        assertEquals(listOf(image), result.images)
        assertTrue("observation and image content stay request-scoped (C2)", result.nonPersistent)
    }

    @Test
    fun `an unknown name is APP_NOT_FOUND and still observes the screen`() {
        val pipeline = ShellPipeline()
        val image = openImage("artifact-not-found")
        var captureCalls = 0
        val result = OpenTool(
            shell = pipeline.shell,
            catalog = FakeCatalog(),
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = { openObservation() },
        ).execute("""{"target":"不存在的应用"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("APP_NOT_FOUND", errorCode(result))
        assertTrue(pipeline.shell.commands.isEmpty())
        assertRefusalExecution(executionOf(result), "APP_NOT_FOUND", recoverable = true)
        assertEquals(1, captureCalls)
        assertEquals(listOf(image), result.images)
        assertEquals(ToolVision.Ok, observationOf(result).vision)
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `an ascii label resolves through the catalog instead of being treated as a package`() {
        val pipeline = ShellPipeline()
        val catalog = FakeCatalog(
            listOf(AppEntry("com.google.android.youtube", "youtube", "android.intent.action.MAIN")),
        )

        val result = launchedOpen(pipeline.shell, catalog, "com.google.android.youtube")
            .execute("""{"target":"youtube"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.google.android.youtube",
            pipeline.shell.commands[0],
        )
        assertEquals(
            "am start -W -n com.google.android.youtube/.MainActivity",
            pipeline.shell.commands[1],
        )
    }

    @Test
    fun `a package-shaped target that is not installed is APP_NOT_FOUND and never launches`() {
        val pipeline = ShellPipeline()

        // R105: the catalog is not the only "is installed" source; the shell probe is the definitive one, and an
        // uninstalled package still must not be launched.
        val result = OpenTool(pipeline.shell, FakeCatalog()).execute("""{"target":"com.nope"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("APP_NOT_FOUND", errorCode(result))
        assertEquals(
            "only the installed probe may run; an uninstalled package must not be launched",
            listOf("pm list packages com.nope"),
            pipeline.shell.commands,
        )
    }

    @Test
    fun `a package target the catalog misses but the shell knows is launched by package`() {
        val shell = FakeShell { command ->
            when {
                command == "pm list packages com.android.fileexplorer" ->
                    ShellResult(command, 0, "package:com.android.fileexplorer\n", "")
                command.startsWith("cmd package resolve-activity") -> ShellResult(
                    command,
                    0,
                    "priority=0 preferredOrder=0\ncom.android.fileexplorer/.FileExplorer\n",
                    "",
                )
                command.startsWith("am start -W") -> ShellResult(
                    command,
                    0,
                    "Starting: Intent { act=android.intent.action.MAIN }\nStatus: ok\n",
                    "",
                )
                else -> ShellResult(command, 1, "", "unexpected command: $command")
            }
        }

        val result = launchedOpen(shell, FakeCatalog(), "com.android.fileexplorer")
            .execute("""{"target":"com.android.fileexplorer"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(
            listOf(
                "pm list packages com.android.fileexplorer",
                "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.android.fileexplorer",
                "am start -W -n com.android.fileexplorer/.FileExplorer",
            ),
            shell.commands,
        )
        assertEquals(
            "the missing catalog label falls back to the package name, never a guess",
            "com.android.fileexplorer",
            body(result)["data"]!!.jsonObject["target"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a file-manager name matching two system apps refuses with structured candidates`() {
        val catalog = FakeCatalog(
            listOf(
                AppEntry("com.android.fileexplorer", "文件管理", "android.intent.action.MAIN"),
                AppEntry("com.android.documentsui", "文件浏览", "android.intent.action.MAIN"),
            ),
        )
        val shell = FakeShell { command -> ShellResult(command, 9, "", "must not run: $command") }

        val result = OpenTool(shell, catalog).execute("""{"target":"文件"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("APP_AMBIGUOUS", errorCode(result))
        assertEquals(
            listOf("com.android.fileexplorer", "com.android.documentsui"),
            body(result)["data"]!!.jsonObject["candidates"]!!.jsonArray
                .map { it.jsonObject["packageName"]!!.jsonPrimitive.content },
        )
        assertTrue("ambiguity must not guess or probe the shell", shell.commands.isEmpty())
    }

    @Test
    fun `a non-http scheme is INVALID_URL and never reaches the shell`() {
        val shell = FakeShell()
        val image = openImage("artifact-invalid-url")
        var captureCalls = 0
        val result = OpenTool(
            shell = shell,
            catalog = FakeCatalog(),
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = { openObservation() },
        ).execute("""{"target":"ftp://example.com/x"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_URL", errorCode(result))
        assertTrue(shell.commands.isEmpty())
        assertRefusalExecution(executionOf(result), "INVALID_URL", recoverable = false)
        assertEquals(1, captureCalls)
        assertEquals(listOf(image), result.images)
        assertEquals(ToolVision.Ok, observationOf(result).vision)
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `the descriptor states the supported target forms and excludes local files`() {
        val descriptor = OpenTool(null, FakeCatalog()).descriptors().single()

        assertTrue("the title must name the supported launch forms", descriptor.title.contains("http(s)"))
        assertTrue("the schema must tell the model that app names/packages/settings pages are accepted",
            descriptor.parametersJson.contains("应用名"))
        assertTrue("the schema must explicitly exclude local files",
            descriptor.parametersJson.contains("file://") && descriptor.parametersJson.contains("不支持"))
        assertTrue("generic category words must be passed unchanged; the tool reports ambiguity",
            descriptor.parametersJson.contains("类别词") && descriptor.parametersJson.contains("不要自行"))
        assertTrue(
            "open touches the screen, so its results must declare a screen observation",
            descriptor.screenObservation,
        )
    }

    // ---- launches ----

    @Test
    fun `a package target resolves the launcher component then starts it`() {
        val pipeline = ShellPipeline()
        val result = launchedOpen(pipeline.shell, knownMusic(), "com.example.music")
            .execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.example.music",
            pipeline.shell.commands[0],
        )
        assertEquals(
            "am start -W -n com.example.music/.MainActivity",
            pipeline.shell.commands[1],
        )
    }

    @Test
    fun `a verified launch reports the foreground, activity, root package and wait`() {
        val pipeline = ShellPipeline()
        val waits = mutableListOf<Pair<String, Long>>()
        val result = launchedOpen(
            shell = pipeline.shell,
            catalog = knownMusic(),
            target = "com.example.music",
            activity = "com.example.music/.MainActivity",
            waitForForeground = { pkg, timeoutMillis ->
                waits += pkg to timeoutMillis
                true
            },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)["data"]!!.jsonObject
        assertEquals("com.example.music", data["resolvedPackage"]!!.jsonPrimitive.content)
        assertEquals(
            "com.example.music",
            data["foreground"]!!.jsonObject["package"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "com.example.music/.MainActivity",
            data["foreground"]!!.jsonObject["activity"]!!.jsonPrimitive.content,
        )
        assertEquals("com.example.music", data["rootPackage"]!!.jsonPrimitive.content)
        assertTrue("foreground and the a11y root both name the target", data["verified"]!!.jsonPrimitive.boolean)
        assertEquals(
            "the existing target key stays beside the verification fields",
            "com.example.music",
            data["target"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "the post-launch wait polls for the resolved package for the full 2 s budget",
            listOf("com.example.music" to 2_000L),
            waits,
        )
    }

    @Test
    fun `verified launch attaches one screenshot and a done observation`() {
        val pipeline = ShellPipeline()
        val image = ImageRef(
            artifactId = "artifact-open-vision",
            mime = "image/jpeg",
            width = 720,
            height = 1600,
            sha256 = "sha256-open-vision",
        )
        var captureCalls = 0
        val result = launchedOpen(
            shell = pipeline.shell,
            catalog = knownMusic(),
            target = "com.example.music",
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = { openObservation() },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("one capture per verified launch", 1, captureCalls)
        assertEquals("the captured artifact is the result's image", listOf(image), result.images)
        assertTrue("an attached image must stay request-scoped (C2)", result.nonPersistent)
        val data = body(result)["data"]!!.jsonObject
        assertEquals("a verified launch marks vision ok", "ok", data["vision"]!!.jsonPrimitive.content)
        assertTrue("the verified launch shape stays intact", data["verified"]!!.jsonPrimitive.boolean)

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertEquals(true, execution.applied)
        assertEquals(true, execution.verified)
        assertEquals("a verified launch is the screen change", true, execution.changed)
        assertFalse(execution.uncertain)
        assertFalse(execution.partial)

        val observation = observationOf(result)
        assertEquals("snap-open", observation.snapshotId)
        assertEquals("com.android.launcher", observation.foregroundPackage)
        assertTrue(observation.screen!!.contains("桌面"))
        assertEquals("an attached image is the only reason vision says ok", ToolVision.Ok, observation.vision)
        assertNull(observation.visionReason)
    }

    @Test
    fun `an unverified launch observes the current screen and attaches one image`() {
        val pipeline = ShellPipeline()
        val image = openImage("artifact-state-unchanged")
        var captureCalls = 0
        val result = OpenTool(
            shell = pipeline.shell,
            catalog = knownMusic(),
            foreground = { "com.android.launcher" },
            foregroundActivity = { "com.android.launcher/.Launcher" },
            rootPackage = { "com.android.launcher" },
            waitForForeground = { _, _ -> false },
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = { openObservation() },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals("T7: the outcome stays a failure", ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", errorCode(result))
        assertEquals("T7: a failed launch must show the model the screen it left behind", 1, captureCalls)
        assertEquals("one image at most", listOf(image), result.images)
        assertTrue(result.nonPersistent)

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("STATE_UNCHANGED", execution.code)
        assertEquals(true, execution.recoverable)
        assertEquals("the launch command was accepted", true, execution.accepted)
        assertNull("the evidence does not prove the launch applied", execution.applied)
        assertEquals("the foreground/root check is the independent evidence", false, execution.verified)
        assertNull("a failed verification claims no screen change", execution.changed)

        val observation = observationOf(result)
        assertEquals("the observation is the failed launch's screen", "com.android.launcher", observation.foregroundPackage)
        assertEquals(ToolVision.Ok, observation.vision)

        val data = body(result)["data"]!!.jsonObject
        assertEquals("the failure data keeps its existing shape", false, data["verified"]!!.jsonPrimitive.boolean)
        assertFalse("failure data gains no vision marker", data.containsKey("vision"))
    }

    @Test
    fun `already foreground observes the screen without relaunching`() {
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }
        val image = openImage("artifact-already-foreground")
        var captureCalls = 0
        var observeCalls = 0
        val result = OpenTool(
            shell = shell,
            catalog = knownMusic(),
            foreground = { "com.example.music" },
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = {
                observeCalls++
                openObservation()
            },
        ).execute("""{"target":"com.example.music","relaunch":false}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue("already-foreground must not launch", shell.commands.isEmpty())
        assertEquals("already-foreground still observes", 1, captureCalls)
        assertEquals(1, observeCalls)
        assertEquals("one image at most", listOf(image), result.images)
        assertTrue(result.nonPersistent)

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertEquals("nothing was launched, so nothing applied", false, execution.applied)
        assertNull("there is no post-condition to verify", execution.verified)
        assertEquals("the screen was already the target: nothing changed", false, execution.changed)

        val observation = observationOf(result)
        assertEquals("com.android.launcher", observation.foregroundPackage)
        assertEquals(ToolVision.Ok, observation.vision)
        val data = body(result)["data"]!!.jsonObject
        assertTrue("the alreadyForeground shape stays intact", data["alreadyForeground"]!!.jsonPrimitive.boolean)
        assertFalse("the alreadyForeground data gains no vision marker", data.containsKey("vision"))
    }

    @Test
    fun `disabled capture still returns done with a disabled observation`() {
        val pipeline = ShellPipeline()
        var captureCalls = 0
        val result = launchedOpen(
            shell = pipeline.shell,
            catalog = knownMusic(),
            target = "com.example.music",
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Disabled
            },
            observe = { openObservation() },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals("a disabled capture never fails the launch", ActionOutcome.Done, result.outcome)
        assertEquals(1, captureCalls)
        assertEquals("disabled", body(result)["data"]!!.jsonObject["vision"]!!.jsonPrimitive.content)
        assertTrue("a disabled capture attaches no image", result.images.isEmpty())
        assertTrue("the observation still exists, so its content is non-persistent", result.nonPersistent)
        val observation = observationOf(result)
        assertEquals("no attached image may claim vision ok", ToolVision.Disabled, observation.vision)
        assertNull(observation.visionReason)
        assertEquals("com.android.launcher", observation.foregroundPackage)
    }

    @Test
    fun `an unavailable capture still returns done with its reason on the observation`() {
        val pipeline = ShellPipeline()
        var captureCalls = 0
        val result = launchedOpen(
            shell = pipeline.shell,
            catalog = knownMusic(),
            target = "com.example.music",
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Unavailable("no_image_profile")
            },
            observe = { openObservation() },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals("an unavailable capture never fails the launch", ActionOutcome.Done, result.outcome)
        assertEquals(1, captureCalls)
        val data = body(result)["data"]!!.jsonObject
        assertEquals(
            "the provider's reason travels to the model",
            "unavailable(no_image_profile)",
            data["vision"]!!.jsonPrimitive.content,
        )
        assertTrue("an unavailable capture attaches no image", result.images.isEmpty())
        assertTrue("the observation still exists, so its content is non-persistent", result.nonPersistent)
        val observation = observationOf(result)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("no_image_profile", observation.visionReason)
    }

    @Test
    fun `a throwing capture is unavailable and still returns done`() {
        val pipeline = ShellPipeline()
        var captureCalls = 0
        val result = launchedOpen(
            shell = pipeline.shell,
            catalog = knownMusic(),
            target = "com.example.music",
            capture = {
                captureCalls++
                throw IllegalStateException("the capture port blew up")
            },
            observe = { openObservation() },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals("a throwing capture must not turn a verified launch into a thrown tool", ActionOutcome.Done, result.outcome)
        assertEquals(1, captureCalls)
        val data = body(result)["data"]!!.jsonObject
        assertEquals(
            "a thrown capture is an unavailable image, not a failed launch",
            "unavailable(SCREENSHOT_FAILED)",
            data["vision"]!!.jsonPrimitive.content,
        )
        assertTrue("a failed capture attaches no image", result.images.isEmpty())
        assertTrue(result.nonPersistent)
        val observation = observationOf(result)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("SCREENSHOT_FAILED", observation.visionReason)
    }

    @Test
    fun `a verified url launch captures the page`() {
        val image = ImageRef(
            artifactId = "artifact-url-vision",
            mime = "image/jpeg",
            width = 720,
            height = 1600,
            sha256 = "sha256-url-vision",
        )
        var captureCalls = 0
        val result = foregroundOpen(
            shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") },
            catalog = FakeCatalog(),
            foreground = "com.android.chrome",
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = { openObservation() },
        ).execute("""{"target":"https://example.com"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("a verified URL launch has a new page to show", 1, captureCalls)
        assertEquals("the page capture is attached", listOf(image), result.images)
        assertTrue("an attached image must stay request-scoped (C2)", result.nonPersistent)
        assertEquals(
            "ok",
            body(result)["data"]!!.jsonObject["vision"]!!.jsonPrimitive.content,
        )
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertEquals(true, execution.applied)
        assertEquals(true, execution.verified)
        assertEquals(true, execution.changed)
        assertEquals(ToolVision.Ok, observationOf(result).vision)
    }

    @Test
    fun `a launch whose foreground matches but a11y root does not is STATE_UNCHANGED`() {
        val pipeline = ShellPipeline()
        val image = openImage("artifact-root-mismatch")
        var captureCalls = 0
        val result = launchedOpen(
            shell = pipeline.shell,
            catalog = knownMusic(),
            target = "com.example.music",
            activity = "com.example.music/.MainActivity",
            root = "com.android.launcher",
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = { openObservation() },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", errorCode(result))
        val body = body(result)
        assertTrue(
            "a launch that did not reach the target is recoverable",
            body["error"]!!.jsonObject["recoverable"]!!.jsonPrimitive.boolean,
        )
        val data = body["data"]!!.jsonObject
        assertFalse("the a11y root disagrees with the target", data["verified"]!!.jsonPrimitive.boolean)
        assertEquals(
            "com.example.music",
            data["foreground"]!!.jsonObject["package"]!!.jsonPrimitive.content,
        )
        assertEquals("com.android.launcher", data["rootPackage"]!!.jsonPrimitive.content)
        assertEquals("com.example.music", data["resolvedPackage"]!!.jsonPrimitive.content)
        assertEquals(
            "the failed launch keeps the existing target data",
            "com.example.music",
            data["target"]!!.jsonPrimitive.content,
        )

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("STATE_UNCHANGED", execution.code)
        assertEquals(true, execution.recoverable)
        assertEquals(true, execution.accepted)
        assertNull("the root mismatch does not prove the launch failed to apply", execution.applied)
        assertEquals(false, execution.verified)
        assertNull(execution.changed)

        assertEquals("T7: the failure shows the screen it actually left", 1, captureCalls)
        assertEquals("one image at most", listOf(image), result.images)
        assertEquals(ToolVision.Ok, observationOf(result).vision)
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `a launch that never reaches the target is STATE_UNCHANGED despite Status ok`() {
        val pipeline = ShellPipeline()
        val waits = mutableListOf<Pair<String, Long>>()
        val result = OpenTool(
            shell = pipeline.shell,
            catalog = knownMusic(),
            foreground = { "com.android.launcher" },
            foregroundActivity = { "com.android.launcher/.Launcher" },
            rootPackage = { "com.android.launcher" },
            waitForForeground = { pkg, timeoutMillis ->
                waits += pkg to timeoutMillis
                false
            },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", errorCode(result))
        val data = body(result)["data"]!!.jsonObject
        assertFalse("am start's Status: ok alone is not success", data["verified"]!!.jsonPrimitive.boolean)
        assertEquals(
            "the observed foreground is reported, not the target",
            "com.android.launcher",
            data["foreground"]!!.jsonObject["package"]!!.jsonPrimitive.content,
        )
        assertEquals("com.android.launcher", data["rootPackage"]!!.jsonPrimitive.content)
        assertEquals("com.example.music", data["resolvedPackage"]!!.jsonPrimitive.content)
        assertEquals(
            "the wait was tried against the resolved package",
            listOf("com.example.music" to 2_000L),
            waits,
        )
        assertTrue(
            "the shell did start the activity; the verification is what failed",
            pipeline.shell.commands.any { it.startsWith("am start -W") },
        )
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("STATE_UNCHANGED", execution.code)
        assertEquals(true, execution.accepted)
        assertNull(execution.applied)
        assertEquals(false, execution.verified)
        assertNull(execution.changed)
    }

    @Test
    fun `an incomplete resolve reply is CHANNEL_UNAVAILABLE`() {
        val shell = FakeShell { command ->
            ShellResult(command, -1, "", "SHELL_NOT_BOUND", false, failure = ShellFailure.NOT_BOUND)
        }

        val result = OpenTool(shell, knownMusic()).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
    }

    @Test
    fun `a resolve exit other than zero is CHANNEL_UNAVAILABLE`() {
        val shell = FakeShell { command -> ShellResult(command, 1, "", "boom") }

        val result = OpenTool(shell, knownMusic()).execute("""{"target":"com.example.music"}""")

        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
    }

    @Test
    fun `no resolved component falls back to a launcher intent`() {
        val pipeline = ShellPipeline(component = { null })
        val result = launchedOpen(pipeline.shell, knownMusic(), "com.example.music")
            .execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.example.music",
            pipeline.shell.commands[1],
        )
    }

    @Test
    fun `a launch without Status ok is a Failed STATE_UNCHANGED and captures`() {
        val pipeline = ShellPipeline(statusOk = false)
        val image = openImage("artifact-no-status")
        var captureCalls = 0

        val result = launchedOpen(
            shell = pipeline.shell,
            catalog = knownMusic(),
            target = "com.example.music",
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = { openObservation() },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", errorCode(result))
        assertFalse(result.detail.contains("Status: ok"))
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("STATE_UNCHANGED", execution.code)
        assertEquals(true, execution.accepted)
        assertEquals("the command explicitly did not start the activity", false, execution.applied)
        assertEquals(false, execution.verified)
        assertNull(execution.changed)
        assertEquals(1, captureCalls)
        assertEquals(listOf(image), result.images)
        assertEquals(ToolVision.Ok, observationOf(result).vision)
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `relaunch false with the target already in front does not start it again`() {
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }

        val result = OpenTool(shell, knownMusic(), foreground = { "com.example.music" })
            .execute("""{"target":"com.example.music","relaunch":false}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue("already-foreground must not launch", shell.commands.isEmpty())
        assertTrue(result.detail.contains("already foreground"))
    }

    @Test
    fun `relaunch true starts the app even when it is already in front`() {
        val pipeline = ShellPipeline()

        // `relaunch=true` must start the app even though the same package is already reported in front.
        val result = foregroundOpen(pipeline.shell, knownMusic(), "com.example.music")
            .execute("""{"target":"com.example.music","relaunch":true}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(2, pipeline.shell.commands.size)
    }

    @Test
    fun `an http URL is single-quote escaped before becoming a shell command`() {
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }

        val result = foregroundOpen(shell, FakeCatalog(), "com.android.browser")
            .execute("""{"target":"https://example.com/a'b"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(
            "am start -W -a android.intent.action.VIEW -d 'https://example.com/a'\\''b'",
            shell.commands.single(),
        )
        val data = body(result)["data"]!!.jsonObject
        assertTrue(data["verified"]!!.jsonPrimitive.boolean)
        assertEquals(
            "a URL has no known target package, so resolvedPackage is the observed foreground",
            "com.android.browser",
            data["resolvedPackage"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an unverified URL launch is STATE_UNCHANGED with the observed foreground`() {
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }
        val waits = mutableListOf<Pair<String, Long>>()
        val result = OpenTool(
            shell = shell,
            catalog = FakeCatalog(),
            foreground = { "com.android.chrome" },
            foregroundActivity = { "com.android.chrome/.Main" },
            rootPackage = { "com.android.launcher" },
            waitForForeground = { pkg, timeoutMillis ->
                waits += pkg to timeoutMillis
                true
            },
        ).execute("""{"target":"https://example.com"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", errorCode(result))
        val data = body(result)["data"]!!.jsonObject
        assertFalse("the a11y root is not the URL's foreground app", data["verified"]!!.jsonPrimitive.boolean)
        assertEquals(
            "no target package is known; the resolved package is the observed foreground",
            "com.android.chrome",
            data["resolvedPackage"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "com.android.chrome",
            data["foreground"]!!.jsonObject["package"]!!.jsonPrimitive.content,
        )
        assertEquals("com.android.launcher", data["rootPackage"]!!.jsonPrimitive.content)
        assertTrue("a URL launch must not poll for an invented target package", waits.isEmpty())
    }

    @Test
    fun `a settings page maps to its android settings action`() {
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }

        val result = foregroundOpen(shell, FakeCatalog(), "com.android.settings")
            .execute("""{"target":"wifi"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("am start -W -a android.settings.WIFI_SETTINGS", shell.commands.single())
        assertTrue(body(result)["data"]!!.jsonObject["verified"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `open without a shell channel is CHANNEL_UNAVAILABLE with the observed screen`() {
        val image = openImage("artifact-channel")
        var captureCalls = 0
        val result = OpenTool(
            shell = null,
            catalog = FakeCatalog(),
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = { openObservation() },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
        assertRefusalExecution(executionOf(result), "CHANNEL_UNAVAILABLE", recoverable = true)
        assertEquals(1, captureCalls)
        assertEquals(listOf(image), result.images)
        assertEquals(ToolVision.Ok, observationOf(result).vision)
        assertTrue(result.nonPersistent)
    }

    // ---- Task 5: execution facts and observations on the remaining paths ----

    @Test
    fun `invalid arguments observe text only and never capture`() {
        var captureCalls = 0
        var observeCalls = 0
        val result = OpenTool(
            shell = FakeShell(),
            catalog = FakeCatalog(),
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Disabled
            },
            observe = {
                observeCalls++
                openObservation()
            },
        ).execute("""{"target":"   "}""")

        assertEquals("an argument refusal stays a refusal", ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_ARGUMENTS", errorCode(result))
        assertEquals("no picture is attempted for a pure validation refusal", 0, captureCalls)
        assertEquals("the refusal still answers with the current screen as text", 1, observeCalls)
        assertTrue(result.images.isEmpty())
        assertTrue("observation text is still perception content (C2)", result.nonPersistent)
        val observation = observationOf(result)
        assertEquals("com.android.launcher", observation.foregroundPackage)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("not_attempted", observation.visionReason)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("INVALID_ARGUMENTS", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
        assertEquals("this refusal is not retryable", false, execution.recoverable)
    }

    @Test
    fun `an unknown action reports execution facts but no observation or image`() {
        var captureCalls = 0
        var observeCalls = 0
        val result = OpenTool(
            shell = null,
            catalog = FakeCatalog(),
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Disabled
            },
            observe = {
                observeCalls++
                openObservation()
            },
        ).execute("shell", "{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", result.detail)
        assertEquals("UNKNOWN_ACTION is not a screen operation", 0, captureCalls)
        assertEquals(0, observeCalls)
        assertNull("an unknown action invents no screen", result.observation)
        assertTrue(result.images.isEmpty())
        assertFalse(result.nonPersistent)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("UNKNOWN_ACTION", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
    }

    @Test
    fun `a screen attempt without an observation source reports unavailable no source`() {
        val result = OpenTool(
            shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") },
            catalog = knownMusic(),
            foreground = { "com.example.music" },
            // No observe source: the production wiring arrives in Task 6.
        ).execute("""{"target":"com.example.music","relaunch":false}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val observation = observationOf(result)
        assertEquals("a missing source is never a silent null", ToolVision.Unavailable, observation.vision)
        assertEquals("no_source", observation.visionReason)
        assertNull(observation.foregroundPackage)
        assertNull(observation.screen)
        assertTrue("no source and a disabled capture attach no image", result.images.isEmpty())
        assertTrue("an attempted observation is non-persistent", result.nonPersistent)
    }

    @Test
    fun `vision is not ok when no image is attached even if the source claims it`() {
        val result = OpenTool(
            shell = null,
            catalog = FakeCatalog(),
            capture = { PerceptionScreenshotResult.Disabled },
            observe = { openObservation(vision = ToolVision.Ok) },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue("no image was attached", result.images.isEmpty())
        assertEquals(
            "vision=ok requires a resolvable attached image, not a source claim",
            ToolVision.Disabled,
            observationOf(result).vision,
        )
    }

    @Test
    fun `a launch command that exits non-zero is STATE_UNCHANGED with its screen`() {
        val shell = FakeShell { command ->
            when {
                command.startsWith("cmd package resolve-activity") ->
                    ShellResult(command, 0, "com.example.music/.MainActivity\n", "")
                command.startsWith("am start -W") -> ShellResult(command, 7, "", "Error: boom")
                else -> ShellResult(command, 0, "", "")
            }
        }
        val image = openImage("artifact-exit-code")
        var captureCalls = 0
        val result = OpenTool(
            shell = shell,
            catalog = knownMusic(),
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
            },
            observe = { openObservation() },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", errorCode(result))
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("STATE_UNCHANGED", execution.code)
        assertEquals(true, execution.accepted)
        assertEquals("the command explicitly failed", false, execution.applied)
        assertEquals(false, execution.verified)
        assertNull(execution.changed)
        assertEquals(1, captureCalls)
        assertEquals(listOf(image), result.images)
        assertEquals(ToolVision.Ok, observationOf(result).vision)
        assertTrue(result.nonPersistent)
    }

    // ---- R98: the state-changing declaration ----

    @Test
    fun `open results declare a state change`() {
        val pipeline = ShellPipeline()
        val done = launchedOpen(pipeline.shell, knownMusic(), "com.example.music")
            .execute("""{"target":"com.example.music"}""")
        assertTrue(done.stateChanging)

        // The declaration describes the call class, not whether the channel was available.
        val refused = OpenTool(null, FakeCatalog()).execute("""{"target":"com.example.music"}""")
        assertTrue("open is a write even when its channel is unavailable", refused.stateChanging)
    }

    // ---- helpers ----

    private companion object {
        /** R104: the launcher enumeration the catalogue uses instead of `pm list packages -3`. */
        const val LAUNCHER_QUERY =
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
    }

    private fun body(result: ActionResult): JsonObject =
        json.parseToJsonElement(result.detail).jsonObject

    private fun errorCode(result: ActionResult): String =
        body(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    /** 0.3 Phase 2.2 Task 5: every `open` result must carry structured execution facts. */
    private fun executionOf(result: ActionResult): ToolExecution {
        assertNotNull("the open result must carry structured execution facts", result.execution)
        return result.execution!!
    }

    /** 0.3 Phase 2.2 Task 5: a screen attempt must carry an observation, not a silent null. */
    private fun observationOf(result: ActionResult): ToolObservation {
        assertNotNull("the open result must carry a post-call observation", result.observation)
        return result.observation!!
    }

    /** The observation facts a production source would return before the tool attaches its own picture. */
    private fun openObservation(vision: ToolVision = ToolVision.NotRequested): ToolObservation = ToolObservation(
        snapshotId = "snap-open",
        capturedAtEpochMillis = 1_700_000_000_000L,
        foregroundPackage = "com.android.launcher",
        foregroundActivity = "com.android.launcher/.Launcher",
        rootPackage = "com.android.launcher",
        coverage = "screen=ok notifications=ok media=ok calendar=ok acoustic=ok clipboard=ok " +
            "vision=ok glasses=ok device=ok",
        screen = "#n1 button \"桌面\"",
        truncated = false,
        vision = vision,
    )

    /** One captured artifact; every image-bearing result must attach exactly this one. */
    private fun openImage(artifactId: String = "artifact-open-observation"): ImageRef = ImageRef(
        artifactId = artifactId,
        mime = "image/jpeg",
        width = 720,
        height = 1600,
        sha256 = "sha256-$artifactId",
    )

    /**
     * The execution facts a tool-level refusal reports: the action was never dispatched, so nothing was accepted or
     * applied, and nothing can be claimed as verified or changed.
     */
    private fun assertRefusalExecution(execution: ToolExecution, code: String, recoverable: Boolean) {
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals(code, execution.code)
        assertEquals(recoverable, execution.recoverable)
        assertEquals("the call never reached its action", false, execution.accepted)
        assertEquals("nothing was applied", false, execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
        assertFalse(execution.uncertain)
        assertFalse(execution.partial)
    }

    /**
     * `com.example.music` has to be a catalog entry now: a package-shaped target is a package only
     * when the catalog actually knows it, and this fake catalog is the catalog under test.
     */
    private fun knownMusic() = FakeCatalog(
        listOf(AppEntry("com.example.music", "Music", "android.intent.action.MAIN")),
    )

    /**
     * An `open` whose foreground only names [target] once the shell has actually run `am start`.
     *
     * That is the real ordering a launch tests: before the command the app is not in front (so the documented
     * `alreadyForeground` shortcut does not swallow the launch), after it the package is what the post-launch
     * verification observes. The wait answers immediately so a JVM test never sleeps for the production 2 s budget;
     * tests that need a mismatch override [root] or inject their own [waitForForeground].
     */
    private fun launchedOpen(
        shell: FakeShell,
        catalog: AppCatalog,
        target: String,
        activity: String? = null,
        root: String? = target,
        waitForForeground: (String, Long) -> Boolean = { _, _ -> true },
        capture: () -> PerceptionScreenshotResult = { PerceptionScreenshotResult.Disabled },
        observe: () -> ToolObservation? = { null },
    ): OpenTool = OpenTool(
        shell = shell,
        catalog = catalog,
        foreground = {
            target.takeIf { shell.commands.any { command -> command.startsWith("am start -W") } }
        },
        foregroundActivity = { activity },
        rootPackage = { root },
        waitForForeground = waitForForeground,
        capture = capture,
        observe = observe,
    )

    /**
     * An `open` with a fixed foreground, for URL/settings launches where no target package exists:
     * the launch verifies that observed foreground against the accessibility root.
     */
    private fun foregroundOpen(
        shell: PrivilegedShell,
        catalog: AppCatalog,
        foreground: String?,
        activity: String? = null,
        root: String? = foreground,
        waitForForeground: (String, Long) -> Boolean = { _, _ -> true },
        capture: () -> PerceptionScreenshotResult = { PerceptionScreenshotResult.Disabled },
        observe: () -> ToolObservation? = { null },
    ): OpenTool = OpenTool(
        shell = shell,
        catalog = catalog,
        foreground = { foreground },
        foregroundActivity = { activity },
        rootPackage = { root },
        waitForForeground = waitForForeground,
        capture = capture,
        observe = observe,
    )

    private class FakeCatalog(
        private val apps: List<AppEntry> = emptyList(),
    ) : AppCatalog {
        override fun visibleApps(): List<AppEntry> = apps
    }

    private class FakeShell(
        private val answer: (String) -> ShellResult = { command -> ShellResult(command, 0, "", "") },
    ) : PrivilegedShell {
        val commands = mutableListOf<String>()

        override fun run(command: String): ShellResult {
            commands += command
            return answer(command)
        }
    }

    /**
     * The resolve-activity/am-start pair a package launch needs. [component] derives the launcher
     * component from the package in the command by default; `{ null }` scripts an empty resolve
     * reply, which exercises the launcher-intent fallback. Every command is recorded by [shell].
     */
    private class ShellPipeline(
        private val component: (String) -> String? = { pkg -> "$pkg/.MainActivity" },
        private val statusOk: Boolean = true,
    ) {
        val shell = FakeShell { command ->
            when {
                command.startsWith("cmd package resolve-activity") -> {
                    val pkg = command.substringAfterLast(' ')
                    ShellResult(
                        command,
                        0,
                        "priority=0 preferredOrder=0\n${component(pkg) ?: ""}\n",
                        "",
                    )
                }
                command.startsWith("am start -W") ->
                    ShellResult(
                        command,
                        0,
                        "Starting: Intent { act=android.intent.action.MAIN }\n" +
                            (if (statusOk) "Status: ok\n" else "Error: Activity not started\n"),
                        "",
                    )
                else -> ShellResult(command, 0, "", "")
            }
        }
    }
}
