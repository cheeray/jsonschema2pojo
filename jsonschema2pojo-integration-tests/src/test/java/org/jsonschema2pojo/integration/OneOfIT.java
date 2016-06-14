/**
 * Copyright © 2010-2014 Nokia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jsonschema2pojo.integration;

import static java.lang.reflect.Modifier.isPublic;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.lang.reflect.InvocationTargetException;

import org.jsonschema2pojo.integration.util.Jsonschema2PojoRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

@SuppressWarnings("rawtypes")
public class OneOfIT {

	@ClassRule
	public static Jsonschema2PojoRule classSchemaRule = new Jsonschema2PojoRule();
	@Rule
	public Jsonschema2PojoRule schemaRule = new Jsonschema2PojoRule();

	@Test
	@SuppressWarnings("unchecked")
	public void oneOfAtRootCreatesATopLevelType()
			throws ClassNotFoundException, NoSuchMethodException,
			IllegalAccessException, InvocationTargetException {

		ClassLoader resultsClassLoader = schemaRule.generateAndCompile(
				"/schema/oneOf/oneOfAsRoot.json", "com.example");

		Class<Enum> rootEnumClass = (Class<Enum>) resultsClassLoader
				.loadClass("com.example.Animal$ONE_OF");

		assertThat(rootEnumClass.isEnum(), is(true));
		assertThat(isPublic(rootEnumClass.getModifiers()), is(true));

	}

}
