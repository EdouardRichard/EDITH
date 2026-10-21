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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `list without a shell channel is CHANNEL_UNAVAILABLE`() {
        val result = OpenTool(null, FakeCatalog()).execute("""{"list":true}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
    }

    // ---- resolution ----

    @Test
    fun `an ambiguous name refuses with structured candidates`() {
        val catalog = FakeCatalog(
            listOf(
                AppEntry("com.example.music1", "音乐", "android.intent.action.MAIN"),
                AppEntry("com.example.music2", "音乐", "android.intent.action.MAIN"),
            ),
        )
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }

        val result = OpenTool(shell, catalog).execute("""{"target":"音乐"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
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
    }

    @Test
    fun `an unknown name is APP_NOT_FOUND`() {
        val pipeline = ShellPipeline()
        val result = OpenTool(pipeline.shell, FakeCatalog()).execute("""{"target":"不存在的应用"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("APP_NOT_FOUND", errorCode(result))
        assertTrue(pipeline.shell.commands.isEmpty())
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

        val result = OpenTool(shell, FakeCatalog()).execute("""{"target":"ftp://example.com/x"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_URL", errorCode(result))
        assertTrue(shell.commands.isEmpty())
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
    fun `verified launch attaches one screenshot and vision ok`() {
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
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("one capture per verified launch", 1, captureCalls)
        assertEquals("the captured artifact is the result's image", listOf(image), result.images)
        assertTrue("an attached image must stay request-scoped (C2)", result.nonPersistent)
        val data = body(result)["data"]!!.jsonObject
        assertEquals("a verified launch marks vision ok", "ok", data["vision"]!!.jsonPrimitive.content)
        assertTrue("the verified launch shape stays intact", data["verified"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `unverified launch does not capture`() {
        val pipeline = ShellPipeline()
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
                PerceptionScreenshotResult.Disabled
            },
        ).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", errorCode(result))
        assertEquals("a launch that is not verified in front must not capture", 0, captureCalls)
        assertTrue("no image may be attached to an unverified launch", result.images.isEmpty())
        assertFalse(
            "an unverified answer has no vision marker",
            body(result)["data"]!!.jsonObject.containsKey("vision"),
        )
    }

    @Test
    fun `already foreground does not capture`() {
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }
        var captureCalls = 0
        val result = OpenTool(
            shell = shell,
            catalog = knownMusic(),
            foreground = { "com.example.music" },
            capture = {
                captureCalls++
                PerceptionScreenshotResult.Disabled
            },
        ).execute("""{"target":"com.example.music","relaunch":false}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue("already-foreground must not launch", shell.commands.isEmpty())
        assertEquals("nothing was brought to front, so there is nothing to capture", 0, captureCalls)
        assertTrue("alreadyForeground attaches no image", result.images.isEmpty())
        assertFalse(
            "the alreadyForeground answer has no vision marker",
            body(result)["data"]!!.jsonObject.containsKey("vision"),
        )
    }

    @Test
    fun `disabled capture still returns done`() {
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
        ).execute("""{"target":"com.example.music"}""")

        assertEquals("a disabled capture never fails the launch", ActionOutcome.Done, result.outcome)
        assertEquals(1, captureCalls)
        assertEquals("disabled", body(result)["data"]!!.jsonObject["vision"]!!.jsonPrimitive.content)
        assertTrue("a disabled capture attaches no image", result.images.isEmpty())
        assertFalse("no image means no non-persistent marker", result.nonPersistent)
    }

    @Test
    fun `a launch whose foreground matches but a11y root does not is STATE_UNCHANGED`() {
        val pipeline = ShellPipeline()
        val result = launchedOpen(
            shell = pipeline.shell,
            catalog = knownMusic(),
            target = "com.example.music",
            activity = "com.example.music/.MainActivity",
            root = "com.android.launcher",
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
    fun `a launch without Status ok is a Failed STATE_UNCHANGED`() {
        val pipeline = ShellPipeline(statusOk = false)

        val result = OpenTool(pipeline.shell, knownMusic()).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", errorCode(result))
        assertFalse(result.detail.contains("Status: ok"))
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
    fun `open without a shell channel is CHANNEL_UNAVAILABLE`() {
        val result = OpenTool(null, FakeCatalog()).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
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
    ): OpenTool = OpenTool(
        shell = shell,
        catalog = catalog,
        foreground = { foreground },
        foregroundActivity = { activity },
        rootPackage = { root },
        waitForForeground = waitForForeground,
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
