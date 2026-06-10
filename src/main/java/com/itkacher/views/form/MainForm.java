/*
 * Copyright 2018 LocaleBro.com [Ievgenii Tkachenko(gektor650@gmail.com)]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.itkacher.views.form;

import com.itkacher.Resources;
import com.itkacher.data.DebugDevice;
import com.itkacher.data.DebugProcess;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

public class MainForm {
    private final JPanel panel;
    private final JComboBox<DebugDevice> deviceList;
    private final JComboBox<DebugProcess> appList;
    private final JPanel mainContainer;
    private final JEditorPane initialHtml;
    private final JPanel buttonContainer;
    private final JButton scrollToBottomButton;
    private final JButton clearButton;
    private final JButton localizeButton;
    private final JButton donateButton;

    public MainForm() {
        // --- UI hierarchy (previously defined by MainForm.form / GUI Designer) ---
        panel = new JPanel(new BorderLayout());

        JPanel topBar = new JPanel(new GridBagLayout());
        deviceList = new JComboBox<>();
        deviceList.setPreferredSize(new Dimension(325, 30));
        appList = new JComboBox<>();
        appList.setPreferredSize(new Dimension(300, 30));
        buttonContainer = new JPanel(new GridBagLayout());

        GridBagConstraints barConstraints = new GridBagConstraints();
        barConstraints.gridy = 0;
        barConstraints.insets = new Insets(0, 4, 0, 4);
        barConstraints.anchor = GridBagConstraints.WEST;

        barConstraints.gridx = 0;
        barConstraints.weightx = 0;
        barConstraints.fill = GridBagConstraints.NONE;
        topBar.add(new JLabel(Resources.Companion.getString("label_device")), barConstraints);

        barConstraints.gridx = 1;
        barConstraints.weightx = 0.5;
        barConstraints.fill = GridBagConstraints.HORIZONTAL;
        topBar.add(deviceList, barConstraints);

        barConstraints.gridx = 2;
        barConstraints.weightx = 0;
        barConstraints.fill = GridBagConstraints.NONE;
        topBar.add(new JLabel(Resources.Companion.getString("label_process")), barConstraints);

        barConstraints.gridx = 3;
        barConstraints.weightx = 0.5;
        barConstraints.fill = GridBagConstraints.HORIZONTAL;
        topBar.add(appList, barConstraints);

        barConstraints.gridx = 4;
        barConstraints.weightx = 0;
        barConstraints.fill = GridBagConstraints.NONE;
        barConstraints.anchor = GridBagConstraints.EAST;
        topBar.add(buttonContainer, barConstraints);

        mainContainer = new JPanel(new BorderLayout());
        initialHtml = new JEditorPane();
        mainContainer.add(new JScrollPane(initialHtml), BorderLayout.CENTER);

        panel.add(topBar, BorderLayout.NORTH);
        panel.add(mainContainer, BorderLayout.CENTER);

        // --- Buttons inside the button container ---
        localizeButton = new JButton();
        localizeButton.setPreferredSize(new Dimension(200, 30));
        localizeButton.setIcon(Resources.Companion.getIcon("localebro.png"));
        localizeButton.setText(Resources.Companion.getString("localize"));
        GridBagConstraints localeBroConstraints = new GridBagConstraints();
        localeBroConstraints.gridx = 0;
        localeBroConstraints.gridy = 0;

        donateButton = new JButton();
        donateButton.setPreferredSize(new Dimension(100, 30));
        donateButton.setText(Resources.Companion.getString("donate"));
        donateButton.setIcon(Resources.Companion.getIcon("donate.png"));
        GridBagConstraints donateButtonConstraints = new GridBagConstraints();
        donateButtonConstraints.gridx = 1;
        donateButtonConstraints.gridy = 0;

        scrollToBottomButton = new JButton();
        scrollToBottomButton.setIcon(Resources.Companion.getIcon("scroll.png"));
        scrollToBottomButton.setPreferredSize(new Dimension(30, 30));
        GridBagConstraints scrollConstraints = new GridBagConstraints();
        scrollConstraints.gridx = 2;
        scrollConstraints.gridy = 0;

        clearButton = new JButton();
        clearButton.setPreferredSize(new Dimension(30, 30));
        clearButton.setIcon(Resources.Companion.getIcon("delete.png"));
        GridBagConstraints clearConstraints = new GridBagConstraints();
        clearConstraints.gridx = 3;
        clearConstraints.gridy = 0;

        buttonContainer.add(localizeButton, localeBroConstraints);
        buttonContainer.add(donateButton, donateButtonConstraints);
        buttonContainer.add(scrollToBottomButton, scrollConstraints);
        buttonContainer.add(clearButton, clearConstraints);

        initialHtml.setEditorKit(JEditorPane.createEditorKitForContentType("text/html"));
        initialHtml.setEditable(false);

        initialHtml.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    Desktop.getDesktop().browse(e.getURL().toURI());
                } catch (IOException | URISyntaxException e1) {
                    e1.printStackTrace();
                }
            }
        });

        try {
            URL url = new URL("https://itkacher.github.io/OkHttpProfiler/plugin_initial.html");
            initialHtml.setPage(url);
        } catch (IOException e) {
            e.printStackTrace();
            URL initialFile = getClass().getClassLoader().getResource("initial.html");
            if (initialFile != null) {
                try {
                    initialHtml.setPage(initialFile);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    public JPanel getPanel() {
        return panel;
    }

    public JComboBox<DebugDevice> getDeviceList() {
        return deviceList;
    }

    public JComboBox<DebugProcess> getAppList() {
        return appList;
    }

    public JButton getScrollToBottomButton() {
        return scrollToBottomButton;
    }

    public JButton getClearButton() {
        return clearButton;
    }

    public JButton getLocalizeButton() {
        return localizeButton;
    }

    public JButton getDonateButton() {
        return donateButton;
    }

    public JPanel getMainContainer() {
        return mainContainer;
    }

    public JEditorPane getInitialHtml() {
        return initialHtml;
    }
}
