-- Exercise the production motion page with only its mpv property boundary stubbed.
local file = assert(io.open(assert(arg[1]), "rb"))
local source = file:read("*a"):gsub("\r\n", "\n")
file:close()
local body = assert(source:match("\n(local motion_last.-\nend)\n"))
local compile = loadstring or load
local factory = assert(compile("return function(mp, append, format)\n" .. body ..
                              "\nreturn add_motion\nend"))()

local current
local queries = 0
local mp = {
    get_property_native = function(name)
        assert(name == "motion-interpolation-info")
        queries = queries + 1
        return current
    end,
}
local append = function(page, value, options)
    page[#page + 1] = (options.prefix or "") .. tostring(value) .. (options.suffix or "")
end
local add_motion = factory(mp, append, string.format)

local function page(state, epoch, timestamp, submissions, syntheses, target)
    current = state and {
        state = state,
        ["target-fps"] = target or 60,
        ["epoch-ns"] = epoch or 1,
        ["timestamp-ns"] = timestamp or 1e9,
        ["swapchain-submissions"] = submissions or 0,
        ["synthesis-submissions"] = syntheses or 0,
    } or nil
    local text = {}
    local before = queries
    add_motion(text)
    assert(queries == before + 1, "one atomic property read per page")
    return table.concat(text, "\n")
end

local function has(text, expected)
    assert(text:find(expected, 1, true), text .. " missing " .. expected)
end

local function no_rates(text)
    assert(not text:find("MEMC submissions:", 1, true), text)
end

assert(page() == "")
has(page("disabled", 0, 1e9, 0, 0, 0), "MEMC:disabled")
no_rates(page("active", 1, 1e9, 200, 100))
local text = page("active", 1, 2e9, 260, 130)
has(text, "MEMC:active")
has(text, "target 60 fps")
has(text, "MEMC submissions:60.0/s, synthesis 30.0/s")
no_rates(page("active", 1, 2e9, 260, 130))
no_rates(page("active", 2, 3e9, 10, 5))
no_rates(page("active", 2, 4e9, 1, 1))
no_rates(page("active", 2, 3e9, 10, 5))
text = page("paused", 2, 4e9, 10, 5)
has(text, "MEMC:paused")
has(text, "MEMC submissions:0.0/s, synthesis 0.0/s")
has(page("unsupported", 3, 5e9, 0, 0, 120), "MEMC:unsupported")
has(page("error", 3, 6e9, 0, 0), "MEMC:error")
page()
no_rates(page("active", 3, 7e9, 100, 50))
page("disabled", 0, 8e9, 0, 0, 0)
no_rates(page("active", 3, 9e9, 200, 100))

current["last-analysis-width"] = 960
current["last-analysis-height"] = 540
current["last-synthesis-width"] = 1920
current["last-synthesis-height"] = 1080
local resolution = {}
add_motion(resolution)
has(table.concat(resolution, "\n"), "Last motion resolution:960x540 ➜ 1920x1080")
assert(source:match("add_video%(stats%)%s+add_motion%(stats%)%s+add_audio%(stats%)"))
local exporter = assert(source:match("\n(local function default_stats%(_.-)\n" ..
                                    "%-%- Returns an ASS string with extended VO stats"))
local export_callback
local published
mp.add_key_binding = function(_, name, callback)
    assert(name == "export-stats")
    export_callback = callback
end
mp.set_property_native = function(name, value)
    assert(name == "user-data/stats")
    published = value
end
local export_factory = assert(compile("return function(mp, add_motion, " ..
    "eval_ass_formatting, add_header, add_file, add_video_out, add_video, " ..
    "add_audio, finalize_page)\n" .. exporter .. "\nend"))()
local noop = function() end
local finalize_page = function(_, content) return table.concat(content, "\n") end
local before = queries
export_factory(mp, add_motion, noop, noop, noop, noop, noop, noop, finalize_page)
assert(queries == before, "exporter registration must not poll motion state")
assert(export_callback)
export_callback()
assert(queries == before + 1, "exported App page must use one motion snapshot")
has(published.text, "MEMC:active")
has(published.text, "target 60 fps")
print("Motion stats: one snapshot, state, target, submission rates, resets and resolution passed")
