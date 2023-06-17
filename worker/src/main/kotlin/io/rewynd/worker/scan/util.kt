package io.rewynd.worker.scan

import com.fasterxml.jackson.dataformat.xml.XmlMapper


inline fun <reified T> XmlMapper.readValue(string: String): T = this.readValue(string, T::class.java)