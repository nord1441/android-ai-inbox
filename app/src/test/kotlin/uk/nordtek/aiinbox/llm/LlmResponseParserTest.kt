package uk.nordtek.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

class LlmResponseParserTest {
    private val tz = ZoneId.of("Asia/Tokyo")
    private val parser = LlmResponseParser(tz)

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("responses/$name")!!.readText()

    @Test
    fun `parses valid response with event`() {
        val r = parser.parse(fixture("valid.json"))
        assertThat(r).isNotNull()
        assertThat(r!!.title).isEqualTo("明日のミーティング")
        assertThat(r.people).containsExactly("田中")
        assertThat(r.event).isNotNull()
        assertThat(r.event!!.title).isEqualTo("田中さんと打ち合わせ")
        // 2026-05-03T14:00:00+09:00 = 2026-05-03T05:00:00Z
        assertThat(r.event.startMillis).isEqualTo(1777784400000L)
        // 2026-05-03T15:00:00+09:00 = 2026-05-03T06:00:00Z
        assertThat(r.event.endMillis).isEqualTo(1777788000000L)
    }

    @Test
    fun `parses response without event`() {
        val r = parser.parse(fixture("no_event.json"))!!
        assertThat(r.event).isNull()
    }

    @Test
    fun `parses event with date only`() {
        val r = parser.parse(fixture("with_event_date_only.json"))!!
        assertThat(r.event).isNotNull()
        // 2026-06-15 00:00 JST = 2026-06-14T15:00:00Z
        assertThat(r.event!!.startMillis).isEqualTo(1781449200000L)
        assertThat(r.event.endMillis).isNull()
    }

    @Test
    fun `extracts JSON from inside markdown code fence`() {
        val s = "ここに少し説明\n```json\n" + fixture("no_event.json") + "\n```\n以上"
        val r = parser.parse(s)
        assertThat(r).isNotNull()
        assertThat(r!!.title).isEqualTo("ランチ感想")
    }

    @Test
    fun `extracts JSON with nested event object from markdown code fence`() {
        // Lazy-quantified regex must extend past inner '}' of the event sub-object.
        val s = "Sure!\n```json\n" + fixture("valid.json") + "\n```"
        val r = parser.parse(s)
        assertThat(r).isNotNull()
        assertThat(r!!.event).isNotNull()
        assertThat(r.event!!.title).isEqualTo("田中さんと打ち合わせ")
    }

    @Test
    fun `returns null for malformed input`() {
        assertThat(parser.parse(fixture("malformed_json.json"))).isNull()
        assertThat(parser.parse("")).isNull()
    }
}
