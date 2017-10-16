/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.protocols.mgcp.stack.test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;

import junit.framework.TestCase;
import junit.framework.TestResult;

import org.apache.logging.log4j.Logger;

public class TestHarness extends TestCase {

	protected static final String ABORT_ON_FAIL = "org.mobicents.mgcp.stack.test.ABORT_ON_FAIL";

	protected static final String LOG_FILE_NAME = "org.mobicents.mgcp.stack.test.LOG_FILE";

	protected static final String LOCAL_ADDRESS = "127.0.0.1";

	protected static final int CA_PORT = 2724;

	protected static final int MGW_PORT = 2727;

	protected static String logFileName = "mgcplog.txt";

	protected static boolean abortOnFail = true;

	private static boolean testPassed = true;

	protected static int testCounter;

	private static Logger logger = org.apache.logging.log4j.LogManager.getLogger("mgcp.test");

	private static String currentMethodName;

	private static String currentClassName;

	protected TestResult testResult;

	// protected static Appender console = new ConsoleAppender(new
	// SimpleLayout());

	public void init() {
		try {
			Properties tckProperties = new Properties();

			InputStream is = getClass().getResourceAsStream("/test.properties");
			System.out.println("Input Stream = " + is);

			tckProperties.load(is);

			Enumeration props = tckProperties.propertyNames();
			while (props.hasMoreElements()) {
				String propname = (String) props.nextElement();
				System.setProperty(propname, tckProperties.getProperty(propname));
			}

			String flag = System.getProperties().getProperty(ABORT_ON_FAIL);

			String lf = System.getProperties().getProperty(LOG_FILE_NAME);
			if (lf != null)
				logFileName = lf;
			abortOnFail = (flag != null && flag.equalsIgnoreCase("true"));

			// JvB: init log4j
			// PropertyConfigurator.configure("log4j.properties");


		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}

	}

	protected TestHarness() {
		init();

	}

	public TestHarness(String name) {
		this(name, false); // default: disable auto-dialog		
	}

	protected TestHarness(String name, boolean autoDialog) {
		super(name);
		this.testResult = new TestResult();
		init();
	}



	public void logTestCompleted() {

		logger.info(this.getName() + " Completed");

	}

	public void logTestCompleted(String info) {
		logger.info(this.getName() + ":" + info + " Completed");

	}

	public void setUp() throws Exception {
		testPassed = true;
	}

	public void tearDown() throws Exception {
		assertTrue("Test failed. See log for details.", testPassed);
	}

}
