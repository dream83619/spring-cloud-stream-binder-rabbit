/*
 * Copyright 2015 the original author or authors.
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

package org.springframework.cloud.stream.binder.rabbit.config;

import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.amqp.support.postprocessor.DelegatingDecompressingPostProcessor;
import org.springframework.amqp.support.postprocessor.GZipPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.binder.rabbit.RabbitMessageChannelBinder;
import org.springframework.cloud.stream.binder.rabbit.properties.RabbitBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.rabbit.properties.RabbitExtendedBindingProperties;
import org.springframework.cloud.stream.binder.rabbit.provisioning.RabbitExchangeQueueProvisioner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Configuration class for RabbitMQ message channel binder.
 *
 * @author David Turanski
 * @author Vinicius Carvalho
 * @author Artem Bilan
 */

@Configuration
@Import({ PropertyPlaceholderAutoConfiguration.class })
@EnableConfigurationProperties({ RabbitBinderConfigurationProperties.class, RabbitExtendedBindingProperties.class })
public class RabbitMessageChannelBinderConfiguration {

	@Autowired
	private ConfigurableApplicationContext applicationContext;

	@Autowired
	private ConnectionFactory rabbitConnectionFactory;

	@Autowired
	private RabbitProperties rabbitProperties;

	@Autowired
	private RabbitBinderConfigurationProperties rabbitBinderConfigurationProperties;

	@Autowired
	private RabbitExtendedBindingProperties rabbitExtendedBindingProperties;

	@Bean
	RabbitMessageChannelBinder rabbitMessageChannelBinder(
			@Qualifier("producerConnectionFactory") ObjectProvider<ConnectionFactory> producerConnectionFactory)
			throws Exception {

		RabbitMessageChannelBinder binder = new RabbitMessageChannelBinder(this.rabbitConnectionFactory,
				this.rabbitProperties, provisioningProvider());
		binder.setProducerConnectionFactory(obtainProducerConnectionFactory(producerConnectionFactory));
		binder.setAdminAddresses(this.rabbitBinderConfigurationProperties.getAdminAddresses());
		binder.setCompressingPostProcessor(gZipPostProcessor());
		binder.setDecompressingPostProcessor(deCompressingPostProcessor());
		binder.setNodes(this.rabbitBinderConfigurationProperties.getNodes());
		binder.setExtendedBindingProperties(this.rabbitExtendedBindingProperties);
		return binder;
	}

	private ConnectionFactory obtainProducerConnectionFactory(
			ObjectProvider<ConnectionFactory> connectionFactoryObjectProvider) throws Exception {

		ConnectionFactory connectionFactory = connectionFactoryObjectProvider.getIfAvailable();

		if (connectionFactory != null) {
			return connectionFactory;
		}
		else {
			CachingConnectionFactory producerConnectionFactory = buildProducerConnectionFactory();
			producerConnectionFactory.setApplicationContext(this.applicationContext);
			this.applicationContext.addApplicationListener(producerConnectionFactory);
			producerConnectionFactory.afterPropertiesSet();

			return producerConnectionFactory;
		}
	}

	/**
	 * @see org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration.RabbitConnectionFactoryCreator
	 */
	private CachingConnectionFactory buildProducerConnectionFactory() throws Exception {
		com.rabbitmq.client.ConnectionFactory rabbitConnectionFactory;
		if (this.rabbitConnectionFactory instanceof CachingConnectionFactory) {
			rabbitConnectionFactory =
					((CachingConnectionFactory) this.rabbitConnectionFactory).getRabbitConnectionFactory();
		}
		else {
			RabbitConnectionFactoryBean factory = new RabbitConnectionFactoryBean();
			if (this.rabbitProperties.determineHost() != null) {
				factory.setHost(this.rabbitProperties.determineHost());
			}
			factory.setPort(this.rabbitProperties.determinePort());
			if (this.rabbitProperties.determineUsername() != null) {
				factory.setUsername(this.rabbitProperties.determineUsername());
			}
			if (this.rabbitProperties.determinePassword() != null) {
				factory.setPassword(this.rabbitProperties.determinePassword());
			}
			if (this.rabbitProperties.determineVirtualHost() != null) {
				factory.setVirtualHost(this.rabbitProperties.determineVirtualHost());
			}
			if (this.rabbitProperties.getRequestedHeartbeat() != null) {
				factory.setRequestedHeartbeat((int)this.rabbitProperties.getRequestedHeartbeat().getSeconds());
			}
			RabbitProperties.Ssl ssl = this.rabbitProperties.getSsl();
			if (ssl.isEnabled()) {
				factory.setUseSSL(true);
				if (ssl.getAlgorithm() != null) {
					factory.setSslAlgorithm(ssl.getAlgorithm());
				}
				factory.setKeyStore(ssl.getKeyStore());
				factory.setKeyStorePassphrase(ssl.getKeyStorePassword());
				factory.setTrustStore(ssl.getTrustStore());
				factory.setTrustStorePassphrase(ssl.getTrustStorePassword());
			}
			if (this.rabbitProperties.getConnectionTimeout() != null) {
				factory.setConnectionTimeout((int)this.rabbitProperties.getConnectionTimeout().getSeconds());
			}
			factory.afterPropertiesSet();

			rabbitConnectionFactory = factory.getObject();
		}

		CachingConnectionFactory connectionFactory = new CachingConnectionFactory(rabbitConnectionFactory);
		connectionFactory.setAddresses(this.rabbitProperties.determineAddresses());
		connectionFactory.setPublisherConfirms(this.rabbitProperties.isPublisherConfirms());
		connectionFactory.setPublisherReturns(this.rabbitProperties.isPublisherReturns());
		if (this.rabbitProperties.getCache().getChannel().getSize() != null) {
			connectionFactory
					.setChannelCacheSize(this.rabbitProperties.getCache().getChannel().getSize());
		}
		if (this.rabbitProperties.getCache().getConnection().getMode() != null) {
			connectionFactory
					.setCacheMode(this.rabbitProperties.getCache().getConnection().getMode());
		}
		if (this.rabbitProperties.getCache().getConnection().getSize() != null) {
			connectionFactory.setConnectionCacheSize(
					this.rabbitProperties.getCache().getConnection().getSize());
		}
		if (this.rabbitProperties.getCache().getChannel().getCheckoutTimeout() != null) {
			connectionFactory.setChannelCheckoutTimeout(
					this.rabbitProperties.getCache().getChannel().getCheckoutTimeout());
		}
		return connectionFactory;
	}

	@Bean
	MessagePostProcessor deCompressingPostProcessor() {
		return new DelegatingDecompressingPostProcessor();
	}

	@Bean
	MessagePostProcessor gZipPostProcessor() {
		GZipPostProcessor gZipPostProcessor = new GZipPostProcessor();
		gZipPostProcessor.setLevel(this.rabbitBinderConfigurationProperties.getCompressionLevel());
		return gZipPostProcessor;
	}

	@Bean
	RabbitExchangeQueueProvisioner provisioningProvider() {
		return new RabbitExchangeQueueProvisioner(this.rabbitConnectionFactory);
	}

}
