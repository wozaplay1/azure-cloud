// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.chtrembl.petstoreassistant;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.chtrembl.petstoreassistant.model.AzurePetStoreSessionInfo;
import com.chtrembl.petstoreassistant.model.DPResponse;
import com.chtrembl.petstoreassistant.service.AzureAIServices.Classification;
import com.chtrembl.petstoreassistant.service.IAzureAIServices;
import com.chtrembl.petstoreassistant.service.IAzurePetStore;
import com.chtrembl.petstoreassistant.utility.PetStoreAssistantUtilities;
import com.codepoetics.protonpack.collectors.CompletableFutures;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.bot.builder.ActivityHandler;
import com.microsoft.bot.builder.MessageFactory;
import com.microsoft.bot.builder.TurnContext;
import com.microsoft.bot.builder.UserState;
import com.microsoft.bot.schema.Attachment;
import com.microsoft.bot.schema.ChannelAccount;

/**
 * This class implements the functionality of the Bot.
 *
 * <p>
 * This is where application specific logic for interacting with the users would
 * be added. For this
 * sample, the {@link #onMessageActivity(TurnContext)} echos the text back to
 * the user. The {@link
 * #onMembersAdded(List, TurnContext)} will send a greeting to new conversation
 * participants.
 * </p>
 */
@Component
@Primary
public class PetStoreAssistantBot extends ActivityHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PetStoreAssistantBot.class);

    @Autowired
    private IAzureAIServices azureOpenAI;

    @Autowired
    private IAzurePetStore azurePetStore;

    private String WELCOME_MESSAGE = "V1 Hello and welcome to the Azure Pet Store, you can ask me questions about our products, your shopping cart and your order, you can also ask me for information about pet animals. How can I help you?";

    private UserState userState;

    public PetStoreAssistantBot(UserState withUserState) {
        this.userState = withUserState;
    }

    // onTurn processing isn't working with DP, not being used...
    @Override
    public CompletableFuture<Void> onTurn(TurnContext turnContext) {
        return super.onTurn(turnContext)
                .thenCompose(saveResult -> userState.saveChanges(turnContext));
    }

    @Override
    protected CompletableFuture<Void> onMessageActivity(TurnContext turnContext) {
        String text = turnContext.getActivity().getText().toLowerCase();

        // strip out session id and csrf token if one was passed from soul machines
        // sendTextMessage() function
        AzurePetStoreSessionInfo azurePetStoreSessionInfo = PetStoreAssistantUtilities
                .getAzurePetStoreSessionInfo(text);

         //DEBUG ONLY
        if (text.contains("variables")) {
            if(azurePetStoreSessionInfo != null && azurePetStoreSessionInfo.getNewText() != null)
            { 
            text = azurePetStoreSessionInfo.getNewText();
            }
                         return turnContext.sendActivity(
                MessageFactory.text(getVariables()))
                .thenApply(sendResult -> null);
        }
        if (text.contains("session")) {
            
            if(azurePetStoreSessionInfo != null)
            {
             return turnContext.sendActivity(
                MessageFactory.text("your session id is " + azurePetStoreSessionInfo.getSessionID()
                        + " and your csrf token is " + azurePetStoreSessionInfo.getCsrfToken()))
                .thenApply(sendResult -> null);
            }
            else
            {
                 return turnContext.sendActivity(
                MessageFactory.text("no session id or csrf token found"))
                .thenApply(sendResult -> null);
            }
        }
        if (text.contains("card")) {
            if(azurePetStoreSessionInfo != null && azurePetStoreSessionInfo.getNewText() != null)
            { 
            text = azurePetStoreSessionInfo.getNewText();
            }
            String jsonString = "{\"type\":\"buttonWithImage\",\"id\":\"buttonWithImage\",\"data\":{\"title\":\"Soul Machines\",\"imageUrl\":\"https://www.soulmachines.com/wp-content/uploads/cropped-sm-favicon-180x180.png\",\"description\":\"Soul Machines is the leader in astonishing AGI\",\"imageAltText\":\"some text\",\"buttonText\":\"push me\"}}";

            Attachment attachment = new Attachment();
            attachment.setContentType("application/json");

            attachment.setContent(new Gson().fromJson(jsonString, JsonObject.class));
            attachment.setName("public-content-card");

            return turnContext.sendActivity(
                    MessageFactory.attachment(attachment, "I have something nice to show @showcards(content-card) you."))
                    .thenApply(sendResult -> null);
        }
        //END DEBUG

        if (azurePetStoreSessionInfo != null) {
            text = azurePetStoreSessionInfo.getNewText();
        } else {
            return turnContext.sendActivity(
                    MessageFactory.text("")).thenApply(sendResult -> null);
        }

        DPResponse dpResponse = this.azureOpenAI.classification(text);

        if (dpResponse.getClassification() == null) {
            dpResponse.setClassification(Classification.SEARCH_FOR_PRODUCTS);
            dpResponse = this.azureOpenAI.search(text, dpResponse.getClassification());
        }

        switch (dpResponse.getClassification()) {
            case UPDATE_SHOPPING_CART:
                if (azurePetStoreSessionInfo != null) {
                    dpResponse = this.azureOpenAI.search(text, Classification.SEARCH_FOR_PRODUCTS);
                    if (dpResponse.getProducts() != null) {
                        dpResponse = this.azurePetStore.updateCart(azurePetStoreSessionInfo,
                                dpResponse.getProducts().get(0).getProductId());
                    }
                }
                break;
            case VIEW_SHOPPING_CART:
                if (azurePetStoreSessionInfo != null) {
                    dpResponse = this.azurePetStore.viewCart(azurePetStoreSessionInfo);
                }
                break;
            case PLACE_ORDER:
                if (azurePetStoreSessionInfo != null) {
                    dpResponse = this.azurePetStore.completeCart(azurePetStoreSessionInfo);
                }
                break;
            case SEARCH_FOR_DOG_FOOD:
            case SEARCH_FOR_DOG_TOYS:
            case SEARCH_FOR_CAT_FOOD:
            case SEARCH_FOR_CAT_TOYS:
            case SEARCH_FOR_FISH_FOOD:
            case SEARCH_FOR_FISH_TOYS:
            case SEARCH_FOR_PRODUCTS:
                dpResponse = this.azureOpenAI.search(text, dpResponse.getClassification());
                break;
            case SOMETHING_ELSE:
                dpResponse = this.azureOpenAI.completion(text, dpResponse.getClassification());
                break;
        }

        // only respond to the user if the user sent something (seems to be a bug where
        // initial messages are sent without a prompt while page loads)
        if (dpResponse.getDpResponseText() != null && dpResponse.getDpResponseText().length() > 0) {
            return turnContext.sendActivity(
                    MessageFactory.text(dpResponse.getDpResponseText())).thenApply(sendResult -> null);
        }
        return null;
    }

    @Override
    protected CompletableFuture<Void> onMembersAdded(
            List<ChannelAccount> membersAdded,
            TurnContext turnContext) {

        return membersAdded.stream()
                .filter(
                        member -> !StringUtils
                                .equals(member.getId(), turnContext.getActivity().getRecipient().getId()))
                .map(channel -> turnContext
                        .sendActivity(
                                MessageFactory.text(this.WELCOME_MESSAGE)))
                .collect(CompletableFutures.toFutureList()).thenApply(resourceResponses -> null);
    }

    private String getVariables() {

        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();

        String debug = requestAttributes.toString();

        if (requestAttributes instanceof ServletRequestAttributes) {

            HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();

            try {
                java.util.Enumeration<String> enumeration = request.getHeaderNames();
                debug += " headers: ";
                while (enumeration.hasMoreElements()) {
                    String headerName = enumeration.nextElement();
                    debug += headerName + " ";
                }
            } catch (Exception e) {

            }

            try {
               java.util.Enumeration<String> enumeration = request.getAttributeNames();
                debug += " attributes: ";
                while (enumeration.hasMoreElements()) {
                    String attributeName = enumeration.nextElement();
                    debug += attributeName + " ";
                }
                
            } catch (Exception e) {

            }

            try {
                java.util.Enumeration<String> enumeration = request.getParameterNames();
                debug += " parameters: ";
                while (enumeration.hasMoreElements()) {
                    String parameterName = enumeration.nextElement();
                    debug += parameterName + " ";
                }
            } catch (Exception e) {

            }

            try {
                java.util.Enumeration<String> enumeration = request.getSession().getAttributeNames();
                debug += " session attributes: ";
                while (enumeration.hasMoreElements()) {
                    String attributeName = enumeration.nextElement();
                    debug += attributeName + " ";
                }
            } catch (Exception e) {

            } 
        }
        return debug;
    }
}
