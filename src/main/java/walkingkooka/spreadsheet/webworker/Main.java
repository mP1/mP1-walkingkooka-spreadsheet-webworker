/*
 * Copyright 2020 Miroslav Pokorny (github.com/mP1)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package walkingkooka.spreadsheet.webworker;

import com.google.gwt.core.client.EntryPoint;
import elemental2.dom.MessagePort;
import elemental2.dom.WorkerGlobalScope;
import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsPackage;
import jsinterop.base.Js;
import walkingkooka.Either;
import walkingkooka.collect.map.Maps;
import walkingkooka.math.Fraction;
import walkingkooka.net.HostAddress;
import walkingkooka.net.IpPort;
import walkingkooka.net.UrlParameterName;
import walkingkooka.net.UrlPath;
import walkingkooka.net.UrlQueryString;
import walkingkooka.net.UrlScheme;
import walkingkooka.net.http.HttpStatus;
import walkingkooka.net.http.HttpStatusCode;
import walkingkooka.net.http.server.HttpRequest;
import walkingkooka.net.http.server.HttpResponse;
import walkingkooka.net.http.server.HttpServer;
import walkingkooka.net.http.server.WebFile;
import walkingkooka.net.http.server.browser.BrowserHttpServers;
import walkingkooka.predicate.Predicates;
import walkingkooka.spreadsheet.SpreadsheetId;
import walkingkooka.spreadsheet.meta.SpreadsheetMetadata;
import walkingkooka.spreadsheet.meta.SpreadsheetMetadataPropertyName;
import walkingkooka.spreadsheet.meta.store.SpreadsheetMetadataStore;
import walkingkooka.spreadsheet.meta.store.SpreadsheetMetadataStores;
import walkingkooka.spreadsheet.reference.store.SpreadsheetCellRangeStores;
import walkingkooka.spreadsheet.reference.store.SpreadsheetExpressionReferenceStores;
import walkingkooka.spreadsheet.reference.store.SpreadsheetLabelStores;
import walkingkooka.spreadsheet.security.store.SpreadsheetGroupStores;
import walkingkooka.spreadsheet.security.store.SpreadsheetUserStores;
import walkingkooka.spreadsheet.server.SpreadsheetHttpServer;
import walkingkooka.spreadsheet.store.SpreadsheetCellStores;
import walkingkooka.spreadsheet.store.repo.SpreadsheetStoreRepositories;
import walkingkooka.spreadsheet.store.repo.SpreadsheetStoreRepository;
import walkingkooka.text.CharSequences;
import walkingkooka.tree.expression.FunctionExpressionName;
import walkingkooka.tree.expression.function.ExpressionFunction;
import walkingkooka.tree.expression.function.ExpressionFunctionContext;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Starts the application as a webworker including waiting for messages from the main window.
 */
public final class Main implements EntryPoint {
    @Override
    public void onModuleLoad() {
        startServer(self());
    }

    /**
     * Elemental appears to be missing a getter or field that returns a {@link WorkerGlobalScope}
     */
    @JsMethod(name="self", namespace= JsPackage.GLOBAL)
    private static native WorkerGlobalScope self();

    // VisibleForTesting
    static void startServer(final WorkerGlobalScope worker) {
        final SpreadsheetMetadataStore metadataStore = SpreadsheetMetadataStores.treeMap();

        final SpreadsheetHttpServer server = SpreadsheetHttpServer.with(UrlScheme.HTTP,
                HostAddress.with("localhost"),
                IpPort.HTTP,
                createMetadata("en", metadataStore),
                fractioner(),
                Main::idToFunctions,
                idToRepository(Maps.sorted(), storeRepositorySupplier(metadataStore)),
                fileServer(),
                browserHttpServer(worker),
                Main::spreadsheetMetadataStamper
        );
        server.start();
    }

    /**
     * Creates a function which merges the given {@link Locale} and then saves it to the {@link SpreadsheetMetadataStore}.
     */
    private static Function<Optional<Locale>, SpreadsheetMetadata> createMetadata(final String defaultLocale,
                                                                                  final SpreadsheetMetadataStore store) {
        final SpreadsheetMetadata metadataWithDefaults = SpreadsheetMetadata.NON_LOCALE_DEFAULTS
                .set(SpreadsheetMetadataPropertyName.LOCALE, Locale.forLanguageTag(defaultLocale))
                .loadFromLocale();

        return (locale) ->
                store.save(locale.map(l -> metadataWithDefaults.set(SpreadsheetMetadataPropertyName.LOCALE, l).loadFromLocale())
                        .orElse(metadataWithDefaults));

    }

    private static Function<BigDecimal, Fraction> fractioner() {
        return (n) -> {
            throw new UnsupportedOperationException();
        };
    }

    private static Function<FunctionExpressionName, ExpressionFunction<?, ExpressionFunctionContext>> idToFunctions(final SpreadsheetId id) {
        return Main::functions;
    }

    /**
     * TODO Implement a real function lookup, that only exposes functions that are enabled for a single spreadsheet.
     */
    private static ExpressionFunction<?, ExpressionFunctionContext> functions(final FunctionExpressionName functionName) {
        throw new UnsupportedOperationException("Unknown function: " + functionName);
    }

    /**
     * Retrieves from the cache or lazily creates a {@link SpreadsheetStoreRepository} for the given {@link SpreadsheetId}.
     */
    private static Function<SpreadsheetId, SpreadsheetStoreRepository> idToRepository(final Map<SpreadsheetId, SpreadsheetStoreRepository> idToRepository,
                                                                                      final Supplier<SpreadsheetStoreRepository> repositoryFactory) {
        return (id) -> {
            SpreadsheetStoreRepository repository = idToRepository.get(id);
            if (null == repository) {
                repository = repositoryFactory.get();
                idToRepository.put(id, repository); // TODO add locks etc.
            }
            return repository;
        };
    }

    /**
     * Creates a new {@link SpreadsheetStoreRepository} on demand
     */
    private static Supplier<SpreadsheetStoreRepository> storeRepositorySupplier(final SpreadsheetMetadataStore metadataStore) {
        return () -> SpreadsheetStoreRepositories.basic(
                SpreadsheetCellStores.treeMap(),
                SpreadsheetExpressionReferenceStores.treeMap(),
                SpreadsheetGroupStores.treeMap(),
                SpreadsheetLabelStores.treeMap(),
                SpreadsheetExpressionReferenceStores.treeMap(),
                metadataStore,
                SpreadsheetCellRangeStores.treeMap(),
                SpreadsheetCellRangeStores.treeMap(),
                SpreadsheetUserStores.treeMap());
    }

    /**
     * A fileserver that always returns {@link HttpStatusCode#NOT_FOUND} for any requested file.
     */
    private static Function<UrlPath, Either<WebFile, HttpStatus>> fileServer() {
        return (p) -> Either.right(HttpStatusCode.NOT_FOUND.status());
    }

    /**
     * Creates a {@link BrowserHttpServers}.
     */
    private static Function<BiConsumer<HttpRequest, HttpResponse>, HttpServer> browserHttpServer(final WorkerGlobalScope worker) {
        final MessagePort port = Js.uncheckedCast(worker);

        return (processor) -> BrowserHttpServers.messagePort(processor,
                port,
                Predicates.always(),
                targetOrigin(worker.getLocation().getSearch())); // TODO accept parameter.
    }

    /**
     * Retrieves the targetOrigin query parameter, failing if it is absent.
     */
    private static String targetOrigin(final String queryString) {
        final UrlQueryString urlQueryString = UrlQueryString.with(queryString);
        final UrlParameterName parameter = UrlParameterName.with("targetOrigin");
        return urlQueryString.parameter(parameter).orElseThrow(() -> new IllegalArgumentException("Missing query parameter " + CharSequences.quoteAndEscape(parameter.value()) + " from " + queryString));
    }

    private static SpreadsheetMetadata spreadsheetMetadataStamper(final SpreadsheetMetadata metadata) {
        return metadata;
    }

    /**
     * Stop creation
     */
    private Main() {
        throw new UnsupportedOperationException();
    }
}
