import * as React from 'react';
import PropTypes from 'prop-types';
import { DragSource } from 'react-dnd';
import classnames from 'classnames';

import {
  EnumToolboxItem,
  EnumDataSource,
  EnumDragSource,
} from './constants';

/**
 * Drag source specification
 */
const dataSource = {
  /**
   * Returns a plain JavaScript object describing the data being dragged
   *
   * @param {any} props
   * @returns a plain JavaScript object
   */
  beginDrag(props) {
    return {
      type: EnumToolboxItem.DataSource,
      source: props.source,
      title: props.title,
      iconClass: props.iconClass,
    };
  }
};

/**
 * A presentational component for a toolbox item of type {@link EnumToolboxItem.DataSource}.
 * A data source component can be dropped inside a TripleGeo operation component.
 *
 * @class DataSource
 * @extends {React.Component}
 */
@DragSource(EnumDragSource.DataSource, dataSource, (connect, monitor) => ({
  connectDragSource: connect.dragSource(),
  isDragging: monitor.isDragging()
}))
class DataSource extends React.Component {

  constructor(props) {
    super();
  }

  static propTypes = {
    // Data source description
    title: PropTypes.string.isRequired,

    // Data source icon
    iconClass: PropTypes.string.isRequired,

    // Data source type
    source: function (props, propName, componentName) {
      if (props[propName] == EnumDataSource.HARVESTER) {
        return new Error(`Invalid data source type [${EnumDataSource.HARVESTER}] found. Use [Harvester] component instead.`);
      }
      for (let prop in EnumDataSource) {
        if (EnumDataSource[prop] === props[propName]) {
          return null;
        }
      }
      return new Error(`Invalid value for property [${propName}].`);
    },

    // Injected by React DnD
    connectDragSource: PropTypes.func.isRequired,
    isDragging: PropTypes.bool.isRequired
  };

  render() {
    const { isDragging, connectDragSource } = this.props;

    return connectDragSource(
      <div className={
        classnames({
          "slipo-pd-item": true,
          "slipo-pd-data-source": true,
          "slipo-pd-item-is-dragging": isDragging,
        })
      }>
        <div className="slipo-pd-data-source-icon">
          <i className={this.props.iconClass}></i>
        </div>
        <div className="slipo-pd-item-label">
          {this.props.title}
        </div>
      </div>
    );
  }

}

export default DataSource;
