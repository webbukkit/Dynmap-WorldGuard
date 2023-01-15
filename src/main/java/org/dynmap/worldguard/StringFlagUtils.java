package org.dynmap.worldguard;

import com.google.common.base.Strings;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nullable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringFlagUtils {
	private static final String  KEY_GROUP     = "key";
	private static final String  VALUE_GROUP   = "value";
	private static final Pattern ENTRY_PATTERN = Pattern.compile(
		"[\"']?(?<" + StringFlagUtils.KEY_GROUP +
		">[\\w-]+)[\"']?([:=][\"']?(?<" + StringFlagUtils.VALUE_GROUP +
		">[\\w-]+)[\"']?)?");

	public static JSONObject generateJson(
		final @Nullable String content
	)
	throws JSONException {
		if (Strings.isNullOrEmpty(content)) {
			return new JSONObject();
		}

		String jsonString = content.trim().replaceAll(
			"[ \\t\\v\\r\\n]+",
			""
		);

		if (!jsonString.startsWith("{") && !jsonString.endsWith("}")) {
			final StringBuilder jsonBuilder = new StringBuilder();
			jsonBuilder.append('{');
			String prefix = "";
			for (String entry : jsonString.split("[,;]+")) {
				final Matcher matcher = StringFlagUtils.ENTRY_PATTERN.matcher(
					entry
				);

				if (!matcher.matches()) {
					continue;
				}

				String key   = matcher.group(StringFlagUtils.KEY_GROUP);
				String value = matcher.group(StringFlagUtils.VALUE_GROUP);

				if (
					"yes".equalsIgnoreCase(value) ||
					"allow".equalsIgnoreCase(value)
				) {
					value = Boolean.TRUE.toString();
				}

				boolean boolValue = Boolean.parseBoolean(value);

				jsonBuilder.append(prefix);
				jsonBuilder.append('\"');
				jsonBuilder.append(key);
				jsonBuilder.append("\":");
				jsonBuilder.append(boolValue);

				prefix = ",";
			}
			jsonBuilder.append('}');

			jsonString = jsonBuilder.toString();
		}

		return new JSONObject(jsonString);
	}
}
