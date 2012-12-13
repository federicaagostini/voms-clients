package org.italiangrid.voms.clients.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.asn1.x509.AttributeCertificate;
import org.bouncycastle.openssl.PasswordFinder;
import org.italiangrid.voms.VOMSError;
import org.italiangrid.voms.VOMSValidators;
import org.italiangrid.voms.ac.VOMSACValidator;
import org.italiangrid.voms.ac.ValidationResultListener;
import org.italiangrid.voms.ac.impl.DefaultVOMSValidator;
import org.italiangrid.voms.clients.ProxyInitParams;
import org.italiangrid.voms.clients.strategies.ProxyInitStrategy;
import org.italiangrid.voms.clients.strategies.VOMSCommandsParsingStrategy;
import org.italiangrid.voms.clients.util.PasswordFinders;
import org.italiangrid.voms.clients.util.VOMSProxyPathBuilder;
import org.italiangrid.voms.credential.LoadCredentialsEventListener;
import org.italiangrid.voms.credential.LoadCredentialsStrategy;
import org.italiangrid.voms.credential.VOMSEnvironmentVariables;
import org.italiangrid.voms.credential.impl.DefaultLoadCredentialsStrategy;
import org.italiangrid.voms.request.VOMSACService;
import org.italiangrid.voms.request.VOMSESLookupStrategy;
import org.italiangrid.voms.request.VOMSProtocolListener;
import org.italiangrid.voms.request.VOMSRequestListener;
import org.italiangrid.voms.request.VOMSServerInfoStoreListener;
import org.italiangrid.voms.request.impl.BaseVOMSESLookupStrategy;
import org.italiangrid.voms.request.impl.DefaultVOMSACRequest;
import org.italiangrid.voms.request.impl.DefaultVOMSACService;
import org.italiangrid.voms.request.impl.DefaultVOMSESLookupStrategy;
import org.italiangrid.voms.store.VOMSTrustStore;
import org.italiangrid.voms.store.VOMSTrustStoreStatusListener;
import org.italiangrid.voms.store.impl.DefaultVOMSTrustStore;
import org.italiangrid.voms.util.CertificateValidatorBuilder;
import org.italiangrid.voms.util.CredentialsUtils;

import eu.emi.security.authn.x509.StoreUpdateListener;
import eu.emi.security.authn.x509.ValidationErrorListener;
import eu.emi.security.authn.x509.ValidationResult;
import eu.emi.security.authn.x509.X509CertChainValidatorExt;
import eu.emi.security.authn.x509.X509Credential;
import eu.emi.security.authn.x509.impl.CertificateUtils;
import eu.emi.security.authn.x509.proxy.ProxyCertificate;
import eu.emi.security.authn.x509.proxy.ProxyCertificateOptions;
import eu.emi.security.authn.x509.proxy.ProxyGenerator;
import eu.emi.security.authn.x509.proxy.ProxyPolicy;
import eu.emi.security.authn.x509.proxy.ProxyType;
import eu.emi.security.authn.x509.proxy.ProxyUtils;

/**
 * The default VOMS proxy init behaviour.
 * 
 * @author andreaceccanti
 * 
 */
public class DefaultVOMSProxyInitBehaviour implements ProxyInitStrategy {

	private VOMSCommandsParsingStrategy commandsParser;
	private X509CertChainValidatorExt certChainValidator;
	
	
	private ValidationResultListener validationResultListener;
	private VOMSRequestListener requestListener;
	private ProxyCreationListener proxyCreationListener;
	private VOMSServerInfoStoreListener serverInfoStoreListener;
	private LoadCredentialsEventListener loadCredentialsEventListener;
	private ValidationErrorListener certChainValidationErrorListener;
	private VOMSTrustStoreStatusListener vomsTrustStoreListener;
	private StoreUpdateListener storeUpdateListener;
	private VOMSProtocolListener protocolListener;
	
	public DefaultVOMSProxyInitBehaviour(VOMSCommandsParsingStrategy commandsParser,
			ValidationResultListener validationListener,
			VOMSRequestListener requestListener,
			ProxyCreationListener pxCreationListener,
			VOMSServerInfoStoreListener serverInfoStoreListener,
			LoadCredentialsEventListener loadCredEventListener,
			ValidationErrorListener certChainListener,
			VOMSTrustStoreStatusListener vomsTSListener,
			StoreUpdateListener trustStoreUpdateListener,
			VOMSProtocolListener protocolListener)
			{
		
		this.commandsParser = commandsParser;
		this.validationResultListener = validationListener;
		this.requestListener = requestListener;
		this.proxyCreationListener = pxCreationListener;
		this.serverInfoStoreListener = serverInfoStoreListener;
		this.loadCredentialsEventListener = loadCredEventListener;
		this.certChainValidationErrorListener = certChainListener;
		this.vomsTrustStoreListener = vomsTSListener;
		this.storeUpdateListener = trustStoreUpdateListener;
		this.protocolListener = protocolListener;
	}

	public DefaultVOMSProxyInitBehaviour(VOMSCommandsParsingStrategy commandsParser, InitListenerAdapter listenerAdapter){
		this.commandsParser = commandsParser;
		this.validationResultListener = listenerAdapter;
		this.requestListener = listenerAdapter;
		this.proxyCreationListener = listenerAdapter;
		this.serverInfoStoreListener = listenerAdapter;
		this.loadCredentialsEventListener = listenerAdapter;
		this.certChainValidationErrorListener = listenerAdapter;
		this.vomsTrustStoreListener = listenerAdapter;
		this.storeUpdateListener = listenerAdapter;
		this.protocolListener = listenerAdapter;
	}
	
	
	
	protected void validateUserCredential(ProxyInitParams params, X509Credential cred){
		
		ValidationResult result = certChainValidator.validate(cred.getCertificateChain());
		if (!result.isValid())
			throw new VOMSError("User credential is not valid!");
		
	}
	
	public void initProxy(ProxyInitParams params) {
		
		X509Credential cred = lookupCredential(params);
		if (cred == null)
			throw new VOMSError("No credentials found!");

		if (params.validateUserCredential()){
			initCertChainValidator(params);
			validateUserCredential(params, cred);
		}
		
		List<AttributeCertificate> acs = Collections.emptyList();
		
		if (params.getVomsCommands() != null && !params.getVomsCommands().isEmpty()){
			initCertChainValidator(params);
			acs = getAttributeCertificates(params, cred);
		}

		if (params.verifyAC() && !acs.isEmpty())
			verifyACs(params, acs);
		
		createProxy(params, cred, acs);
	}
	
	private void initCertChainValidator(ProxyInitParams params){
		
		if (certChainValidator == null){
			String trustAnchorsDir = DefaultVOMSValidator.DEFAULT_TRUST_ANCHORS_DIR;
		
			if (System.getenv(VOMSEnvironmentVariables.X509_CERT_DIR) != null)
				trustAnchorsDir = System.getenv(VOMSEnvironmentVariables.X509_CERT_DIR);
			
			if (params.getTrustAnchorsDir()!=null)
				trustAnchorsDir = params.getTrustAnchorsDir();
		
			certChainValidator = CertificateValidatorBuilder.buildCertificateValidator(trustAnchorsDir, 
					certChainValidationErrorListener, storeUpdateListener);
			
		}
	}
	
	private VOMSACValidator initVOMSValidator(ProxyInitParams params){
		
		String vomsdir = DefaultVOMSTrustStore.DEFAULT_VOMS_DIR;
		
		if (System.getenv(VOMSEnvironmentVariables.X509_VOMS_DIR) != null)
			vomsdir = System.getenv(VOMSEnvironmentVariables.X509_VOMS_DIR);
		
		VOMSTrustStore trustStore = new DefaultVOMSTrustStore(Arrays.asList(vomsdir)
				, vomsTrustStoreListener);
		
		return VOMSValidators.newValidator(trustStore, 
				certChainValidator, 
				validationResultListener);
	}
	
	private void verifyACs(ProxyInitParams params, List<AttributeCertificate> acs) {
		
		VOMSACValidator acValidator = initVOMSValidator(params);
		acValidator.validateACs(acs);
	}

	
	private void ensureProxyLifetimeIsConsistentWithIssuingCredential(ProxyCertificateOptions options, 
			X509Credential issuingCredential,
			List<String> proxyCreationWarnings){
		
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.SECOND, options.getLifetime());
		
		Date proxyEndTime = cal.getTime();
		Date issuingCredentialEndTime = issuingCredential.getCertificate().getNotAfter();
		
		if ( proxyEndTime.after(issuingCredentialEndTime) ){
			proxyCreationWarnings.add("proxy lifetime limited to issuing credential lifetime.");
			
			long skew = issuingCredentialEndTime.getTime() - System.currentTimeMillis();
			options.setLifetime(skew, TimeUnit.MILLISECONDS);
		}	
	}
	
	
	private void  createProxy(ProxyInitParams params,
			X509Credential credential, List<AttributeCertificate> acs) {
		
		List<String> proxyCreationWarnings = new ArrayList<String>();
		
		String proxyFilePath = VOMSProxyPathBuilder.buildProxyPath();
		
		if (params.getGeneratedProxyFile() != null)
			proxyFilePath = params.getGeneratedProxyFile();
		
		ProxyCertificateOptions proxyOptions = new ProxyCertificateOptions(credential.getCertificateChain());
		
		proxyOptions.setProxyPathLimit(params.getPathLenConstraint());
		proxyOptions.setLimited(params.isLimited());
		proxyOptions.setLifetime(params.getProxyLifetimeInSeconds());
		proxyOptions.setType(params.getProxyType());
		proxyOptions.setKeyLength(params.getKeySize());
		
		ensureProxyLifetimeIsConsistentWithIssuingCredential(proxyOptions, 
				credential, proxyCreationWarnings);
		
		if (proxyOptions.getType().equals(ProxyType.RFC3820)|| proxyOptions.getType().equals(ProxyType.DRAFT_RFC)){
			ProxyPolicy policy;
			
			if (params.isLimited())
				policy = new ProxyPolicy(ProxyPolicy.LIMITED_PROXY_OID);
			else
				policy = new ProxyPolicy(ProxyPolicy.INHERITALL_POLICY_OID);
			
			proxyOptions.setPolicy(policy);
		}
		if (acs != null && !acs.isEmpty())
			proxyOptions.setAttributeCertificates(acs.toArray(new AttributeCertificate[acs.size()]));
		
		try {
			
			ProxyCertificate proxy = ProxyGenerator.generate(proxyOptions, credential.getKey());
			
			CredentialsUtils.saveProxyCredentials(proxyFilePath, proxy.getCredential());
			proxyCreationListener.proxyCreated(proxyFilePath, proxy, proxyCreationWarnings);
		
		} catch (Throwable t) {
			
			throw new VOMSError("Error creating proxy certificate: "+t.getMessage(), t);
		}
	}

	protected List<String> sortFQANsIfRequested(ProxyInitParams params, List<String> unsortedFQANs){
		
		if (params.getFqanOrder() != null && !params.getFqanOrder().isEmpty()){
			
			Set<String> fqans = new LinkedHashSet<String>();
			
			fqans.addAll(params.getFqanOrder());
			fqans.addAll(unsortedFQANs);
			
			return new ArrayList<String>(fqans);
		}
		
		return unsortedFQANs;
	}

	protected VOMSESLookupStrategy getVOMSESLookupStrategyFromParams(ProxyInitParams params){
		
		if (params.getVomsesLocations() != null && ! params.getVomsesLocations().isEmpty())
			return new BaseVOMSESLookupStrategy(params.getVomsesLocations());
		else
			return new DefaultVOMSESLookupStrategy();
		
	}
	
	protected List<AttributeCertificate> getAttributeCertificates(
			ProxyInitParams params, X509Credential cred) {

		List<String> vomsCommands = params.getVomsCommands();

		if (vomsCommands == null || vomsCommands.isEmpty())
			return Collections.emptyList();

		Map<String, List<String>> vomsCommandsMap = commandsParser
				.parseCommands(params.getVomsCommands());

		List<AttributeCertificate> acs = new ArrayList<AttributeCertificate>();

		for (String vo : vomsCommandsMap.keySet()) {

			DefaultVOMSACRequest request = new DefaultVOMSACRequest();

			List<String> fqans = vomsCommandsMap.get(vo);
			request.setVoName(vo);
			request.setRequestedFQANs(sortFQANsIfRequested(params, fqans));
			request.setTargets(params.getTargets());
			request.setLifetime(params.getAcLifetimeInSeconds());
			
			VOMSACService acService = new DefaultVOMSACService.Builder(certChainValidator)
					.requestListener(requestListener)
					.vomsesLookupStrategy(getVOMSESLookupStrategyFromParams(params))
					.serverInfoStoreListener(serverInfoStoreListener)
					.protocolListener(protocolListener)
					.connectTimeout((int)TimeUnit.SECONDS.toMillis(params.getTimeoutInSeconds()))
					.readTimeout((int)TimeUnit.SECONDS.toMillis(params.getTimeoutInSeconds()))
					.build();
			
			AttributeCertificate ac = acService.getVOMSAttributeCertificate(
					cred, request);

			if (ac != null)
				acs.add(ac);
		}

		if (!vomsCommandsMap.keySet().isEmpty() && acs.isEmpty())
			throw new VOMSError("User's request for VOMS attributes could not be fulfilled.");
		
		return acs;
	}

	
	private LoadCredentialsStrategy strategyFromParams(ProxyInitParams params){
		
		if (params.isNoRegen())
			return new LoadProxyCredential(loadCredentialsEventListener);
		
		if (params.getCertFile()!=null && params.getKeyFile() == null)
			return new LoadUserCredential(loadCredentialsEventListener, params.getCertFile());
		
		if (params.getCertFile()!=null && params.getKeyFile()!=null)
			return new LoadUserCredential(loadCredentialsEventListener, params.getCertFile(), params.getKeyFile());
		
		return new DefaultLoadCredentialsStrategy(System.getProperty(DefaultLoadCredentialsStrategy.HOME_PROPERTY),
					DefaultLoadCredentialsStrategy.TMPDIR_PROPERTY,
					loadCredentialsEventListener);
		
	}
	
	private X509Credential lookupCredential(ProxyInitParams params) {

		PasswordFinder pf = null;

		if (params.isReadPasswordFromStdin())
			pf = PasswordFinders.getNoPromptInputStreamPasswordFinder(System.in, System.out);
		else
			pf = PasswordFinders.getDefault();
		
		LoadCredentialsStrategy loadCredStrategy = strategyFromParams(params);
		
		return loadCredStrategy.loadCredentials(pf);
	}

}
