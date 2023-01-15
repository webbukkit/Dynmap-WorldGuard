package org.dynmap.worldguard;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public class StringFlagUtilsTest {
	@Test
	public void testGenerateJsonEmpty() {
		JSONObject jsonObject = StringFlagUtils.generateJson("");

		Assert.assertTrue(jsonObject.isEmpty());
	}

	@Test
	public void testGenerateJsonMultipleFlags() {
		JSONObject jsonObject = StringFlagUtils.generateJson(
			"greeting:allow,farewell:deny"
		);

		Assert.assertTrue(jsonObject.getBoolean("greeting"));
		Assert.assertFalse(jsonObject.getBoolean("farewell"));
	}
}
