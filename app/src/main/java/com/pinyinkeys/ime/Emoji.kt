package com.pinyinkeys.ime

/**
 * Emoji for the emoji panel, grouped by category. They're drawn with the phone's own
 * emoji font, so nothing is downloaded. Kept as plain strings (some emoji are several
 * characters long, e.g. with skin tones or joiners).
 */
object Emoji {
    data class Category(val icon: String, val name: String, val items: List<String>)

    private fun list(s: String) = s.trim().split(Regex("\\s+"))

    val categories = listOf(
        Category("😀", "Smileys", list("""
            😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 ☺️ 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑
            🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶
            🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 🥹 😦 😧 😨 😰 😥 😢 😭 😱 😖
            😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾
        """)),
        Category("👋", "People", list("""
            👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌
            🫶 👐 🤲 🤝 🙏 💪 🦵 🦶 👂 👃 🧠 👀 👁️ 👅 👄 💋 👶 🧒 👦 👧 🧑 👱 👨 👩 🧓 👴 👵
            🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 👯 🧖 🧘 👫 👭 👬 💏 💑 👪
        """)),
        Category("❤️", "Hearts", list("""
            ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ 💌 💋 🌹
            🥀 💐 🌷 🌸 💮 🌺 🌻 🌼 ✨ 💫 ⭐ 🌟 🔥 💯 💢 💥 💦 💨 🕊️
        """)),
        Category("🐶", "Animals", list("""
            🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🐣 🦆
            🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈
            🐊 🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🦒 🦘 🐃 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🦚 🦜 🐇
            🐁 🐀 🐿️ 🦔 🌲 🌳 🌴 🌵 🌿 🍀 🍁 🍂 🍃 🌾 🌱 🌈 ☀️ 🌤️ ⛅ 🌧️ ⛈️ ❄️ ☃️ 🌙 🌊
        """)),
        Category("🍜", "Food", list("""
            🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄
            🧅 🥔 🍠 🥐 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜
            🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪
            🥛 ☕ 🍵 🧋 🥤 🧃 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🥢 🍽️ 🥄
        """)),
        Category("⚽", "Activities", list("""
            ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🏹 🎣 🛹 ⛸️ 🎿 🏂 🏋️ 🤸 🏊 🚴 🧗 🏆 🥇 🥈
            🥉 🏅 🎖️ 🎫 🎟️ 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 🧩 🎮 🎯 🎳 🎉 🎊 🎈 🎁 🎀
            🚗 🚕 🚌 🚓 🚑 🚲 🛵 🏍️ ✈️ 🚀 🚁 ⛵ 🚢 🚆 🚇 🏠 🏡 🏢 🏫 🏥 🏖️ 🏝️ ⛰️ 🗻 🏕️ 🗼 🎡
        """)),
        Category("💡", "Objects", list("""
            📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 📷 📸 📹 🎥 📞 ☎️ 📺 📻 ⏰ ⌛ ⏳ 💡 🔦 🕯️ 💸 💵 💴 💰 💳 💎
            🔧 🔨 🔩 ⚙️ 🧲 🔫 💣 🔪 🗡️ 🛡️ 🔮 💊 💉 🩹 🧸 🛏️ 🛋️ 🚪 🛁 🧻 🧼 🧽 🧹 🧺 🛒 🎒 👓
            🕶️ 👔 👕 👖 🧣 🧤 🧥 👗 👘 👙 👚 👛 👜 👝 👟 👠 👡 👢 👑 👒 🎩 🎓 💄 💍 ✏️ 📝 📚
            📖 📌 📎 ✂️ 📦 📫 ✉️ 📅 🗓️ 🔒 🔓 🔑 🗝️
        """)),
        Category("🔣", "Symbols", list("""
            ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ⚠️ 🚫 ⛔ 🔞 📵 🔴 🟠 🟡 🟢 🔵 🟣 ⚫
            ⚪ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ 🔶 🔷 🔺 🔻 💠 🔘 ⬆️ ↗️ ➡️ ↘️ ⬇️ ↙️ ⬅️ ↖️ ↕️ ↔️ 🔄 🔃
            🎵 🎶 💤 💬 💭 🗯️ ♠️ ♣️ ♥️ ♦️ 🃏 🀄 🆗 🆒 🆕 🆓 🆙 🆘 ℹ️ 🔝 🔜 #️⃣ *️⃣ 0️⃣ 1️⃣ 2️⃣ 3️⃣
            🇨🇳 🇺🇸 🏳️ 🏴 🏁 🚩 🏳️‍🌈
        """)),
    )
}
